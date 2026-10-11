#!/usr/bin/env python3
"""Representation-only OpenDaylight adapter using graph YANG and PCEP wire I/O."""
from __future__ import annotations
import argparse, base64, hashlib, ipaddress, json, os, re, socket, struct, subprocess, sys, time
import urllib.error, urllib.request, uuid
from pathlib import Path
import sampler_control
from evidence_stream import CompressedJsonArrayWriter

IMPLEMENTATION_COMMIT="abc5fdb1b3743b5b99d38762398e7bea8eebe661"
PINNED_ODL_COMMIT="42ce9509a505a2b113c7a572e57a404d616dd45e"
PINNED_IMAGE_ID="sha256:55a6cd264d0560b869ac665ab420e709810601580764a6daf2e4724119cb05fe"
GRAPH_NAME="ted://example-linkstate-topology"
PINNED_BOOT_FEATURES=("odl-restconf","odl-bgpcep-pcep","odl-bgpcep-pcep-server-provider")
def write(path,value): path.write_text(json.dumps(value,indent=2,sort_keys=True)+"\n",encoding="utf-8")
def run(*args,check=True): return subprocess.run(args,text=True,capture_output=True,check=check)
def object_header(cls,typ,body,processing=True):
    return struct.pack("!BBH",cls,(typ<<4)|(2 if processing else 0),len(body)+4)+body
def message(typ,body=b""): return struct.pack("!BBH",0x20,typ,len(body)+4)+body
def open_message(): return message(1,object_header(1,1,struct.pack("!BBBB",0x20,30,120,1),False))
def request_message(request_id,source,target,bandwidth_bps=None):
    rp=object_header(2,1,struct.pack("!II",0,request_id))
    endpoints=object_header(4,1,ipaddress.ip_address(source).packed+ipaddress.ip_address(target).packed)
    bandwidth=b"" if bandwidth_bps is None else object_header(5,1,struct.pack("!f",float(bandwidth_bps)/8))
    # The pinned ODL server selects CSPF only when a TE-metric constraint is
    # present; bandwidth alone incorrectly falls through to unconstrained SPF.
    # A non-binding TE-metric upper bound activates CSPF while preserving the
    # canonical cost objective and bandwidth semantics.
    te_metric=b"" if bandwidth_bps is None else object_header(6,1,struct.pack("!HBBf",0,1,2,1_000_000_000.0))
    return message(3,rp+endpoints+bandwidth+te_metric)
def recv_exact(sock,count):
    data=b""
    while len(data)<count:
        chunk=sock.recv(count-len(data))
        if not chunk: raise RuntimeError("PCEP peer closed session")
        data+=chunk
    return data
def recv_message(sock,timeout):
    sock.settimeout(timeout); header=recv_exact(sock,4); version,typ,length=struct.unpack("!BBH",header)
    if version>>5!=1 or length<4: raise RuntimeError("malformed PCEP common header")
    return typ,header+recv_exact(sock,length-4)
def establish(host,port,timeout):
    sock=socket.create_connection((host,port),timeout=timeout); sock.sendall(open_message())
    peer_open=peer_keepalive=False
    for _ in range(6):
        kind,_=recv_message(sock,timeout)
        if kind==1: peer_open=True; sock.sendall(message(2))
        elif kind==2: peer_keepalive=True
        elif kind==6: raise RuntimeError("PCErr during PCEP session establishment")
        if peer_open and peer_keepalive: return sock
    raise RuntimeError("PCEP session establishment incomplete")
def iter_objects(packet):
    offset=4
    while offset+4<=len(packet):
        cls,type_flags,length=struct.unpack("!BBH",packet[offset:offset+4])
        if length<4 or offset+length>len(packet): raise RuntimeError("malformed PCEP object")
        yield cls,type_flags>>4,packet[offset+4:offset+length]; offset+=length
    if offset!=len(packet): raise RuntimeError("trailing PCEP object bytes")
def parse_reply(packet,source,ip_to_node,expected_request_id=None):
    ero=None; request_id=None; no_path=False
    for cls,_typ,body in iter_objects(packet):
        if cls==2:
            if len(body)<8: raise RuntimeError("malformed PCRep RP object")
            request_id=struct.unpack("!I",body[4:8])[0]
        if cls==3: no_path=True
        if cls==7: ero=body
    if expected_request_id is not None and request_id!=expected_request_id:
        raise RuntimeError(f"PCRep request ID mismatch: expected {expected_request_id}, received {request_id}")
    if no_path: return "NO_PATH",None
    if ero is None: raise RuntimeError("PCRep contains neither NO-PATH nor ERO")
    path=[ip_to_node[source]]; offset=0
    while offset+2<=len(ero):
        loose_type,length=struct.unpack("!BB",ero[offset:offset+2]); subtype=loose_type&0x7f
        if length<2 or offset+length>len(ero): raise RuntimeError("malformed ERO subobject")
        body=ero[offset+2:offset+length]
        if subtype!=1 or len(body)!=6 or body[4]!=32: raise RuntimeError("unexpected non-IPv4/32 ERO subobject")
        address=str(ipaddress.ip_address(body[:4]))
        if address not in ip_to_node: raise RuntimeError(f"ERO address absent from canonical topology: {address}")
        path.append(ip_to_node[address]); offset+=length
    if offset!=len(ero): raise RuntimeError("trailing ERO bytes")
    return "PATH",path
def convert_topology(canonical):
    ids={node["node_id"]:index for index,node in enumerate(canonical["nodes"],1)}
    ips={node["node_id"]:node["ipv4"] for node in canonical["nodes"]}
    vertices=[{"vertex-id":ids[n["node_id"]],"name":n["node_id"],"router-id":n["ipv4"]} for n in canonical["nodes"]]
    # BGPCEP's ConnectedGraph resolves PCEP END-POINTS through attached
    # prefixes, not through the vertex router-id.  Advertise each canonical
    # node address as a host prefix so PCReq source/destination lookup works.
    prefixes=[{"vertex-id":ids[n["node_id"]],"prefix":f'{n["ipv4"]}/32'} for n in canonical["nodes"]]
    edges=[]
    for edge_id,link in enumerate(canonical["links"],1):
        # RFC 7951 represents YANG decimal64 as a JSON string. Graph bandwidth is bytes/s.
        bandwidth=f'{float(link["bandwidth_bps"])/8:.2f}'
        edges.append({"edge-id":edge_id,"name":link["link_id"],"local-vertex-id":ids[link["source"]],
          "remote-vertex-id":ids[link["target"]],"edge-attributes":{"local-address":ips[link["source"]],
          "remote-address":ips[link["target"]],"metric":int(link["cost"]),"te-metric":{"metric":int(link["cost"]),
          "max-link-bandwidth":bandwidth,"max-resv-link-bandwidth":bandwidth,"unreserved-bandwidth":[
          {"class-type":value,"bandwidth":bandwidth} for value in range(8)]}}})
    return {"graph:graph-topology":{"graph":[{"name":GRAPH_NAME,"domain-scope":"intra-domain",
      "vertex":vertices,"edge":edges,"prefix":prefixes}]}}
def rest_put(port,payload,timeout):
    url=f"http://127.0.0.1:{port}/rests/data/graph:graph-topology"
    auth=base64.b64encode(b"admin:admin").decode(); request=urllib.request.Request(url,
      data=json.dumps(payload,separators=(",",":")).encode(),method="PUT",headers={"Authorization":f"Basic {auth}",
      "Content-Type":"application/yang-data+json"})
    try:
        with urllib.request.urlopen(request,timeout=timeout) as response: return response.status,response.read()
    except urllib.error.HTTPError as error: return error.code,error.read()
    except (urllib.error.URLError,OSError) as error: return None,str(error).encode()
def rest_get_operational(port,timeout):
    url=f"http://127.0.0.1:{port}/rests/data/graph:graph-topology?content=nonconfig"
    auth=base64.b64encode(b"admin:admin").decode(); request=urllib.request.Request(url,
      headers={"Authorization":f"Basic {auth}","Accept":"application/yang-data+json"})
    try:
        with urllib.request.urlopen(request,timeout=timeout) as response: return response.status,response.read()
    except urllib.error.HTTPError as error: return error.code,error.read()
    except (urllib.error.URLError,OSError) as error: return None,str(error).encode()
def graph_seed_payload():
    # The pinned BGPCEP graph implementation connects edges only when they are
    # added to an existing graph.  Seed the graph first, then PUT its children.
    return {"graph:graph-topology":{"graph":[{"name":GRAPH_NAME,"domain-scope":"intra-domain"}]}}
def image_identity(image):
    if not image: return False,None
    inspected=run("docker","image","inspect",image,check=False)
    if inspected.returncode: return False,None
    value=json.loads(inspected.stdout)[0]; labels=value.get("Config",{}).get("Labels") or {}; image_id=value.get("Id")
    eligible=bool(re.fullmatch(r"[^@\s]+@sha256:[0-9a-f]{64}",image) and image_id==PINNED_IMAGE_ID
      and labels.get("org.opencontainers.image.revision")==PINNED_ODL_COMMIT)
    return eligible,{"id":image_id,"repo_digests":value.get("RepoDigests"),"labels":labels}
def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--identity",action="store_true")
    parser.add_argument("--topology",type=Path); parser.add_argument("--warmup-requests",type=Path)
    parser.add_argument("--requests",type=Path); parser.add_argument("--output",type=Path); parser.add_argument("--subject")
    args=parser.parse_args(); image=os.environ.get("ODL_IMAGE",""); eligible,inspection=image_identity(image)
    if args.identity:
        print(json.dumps({"subject":args.subject or "opendaylight","implementation_commit":IMPLEMENTATION_COMMIT,
          "scored_eligible":eligible,"bgpcep_commit":PINNED_ODL_COMMIT,"image":image})); return 0
    if args.subject!="opendaylight" or not all((args.topology,args.warmup_requests,args.requests,args.output)):
        raise ValueError("complete opendaylight adapter arguments required")
    if not image: raise ValueError("ODL_IMAGE is required")
    args.output.mkdir(parents=True,exist_ok=True); canonical=json.loads(args.topology.read_text()); translated=convert_topology(canonical)
    topology_path=args.output/"odl-graph-topology.json"; write(topology_path,translated)
    ip_by_node={row["node_id"]:row["ipv4"] for row in canonical["nodes"]}; node_by_ip={v:k for k,v in ip_by_node.items()}
    name=f"matched-odl-{uuid.uuid4().hex[:12]}"; raw=CompressedJsonArrayWriter(args.output/"raw-responses.json.gz")
    timings=[]; responses=[]; started=time.monotonic_ns(); container_id=None; sampler=None
    try:
        feature_list=",".join(PINNED_BOOT_FEATURES)
        boot_command=("set -eu; sed -i -E 's/^(featuresBoot = .*)$/\\1,"+feature_list+
          "/' /opt/opendaylight/etc/org.apache.karaf.features.cfg; exec /usr/local/bin/odl-entrypoint")
        launched=run("docker","run","-d","--name",name,"--cpus",os.environ.get("MATCHED_CPU_LIMIT","6"),
          "--memory",os.environ.get("MATCHED_MEMORY_LIMIT","12g"),"-p","127.0.0.1::4189","-p","127.0.0.1::8181",
          "--entrypoint","/bin/sh",image,"-c",boot_command)
        container_id=launched.stdout.strip(); pcep_port=rest_port=None; topology_status=None; topology_operational_status=None
        graph_seed_status=None
        for _ in range(180):
            for internal,attr in (("4189/tcp","pcep"),("8181/tcp","rest")):
                mapped=run("docker","port",name,internal,check=False).stdout.strip()
                if mapped:
                    if attr=="pcep": pcep_port=int(mapped.rsplit(":",1)[1])
                    else: rest_port=int(mapped.rsplit(":",1)[1])
            if pcep_port and rest_port:
                if graph_seed_status not in (200,201,204):
                    graph_seed_status,_=rest_put(rest_port,graph_seed_payload(),3)
                if graph_seed_status in (200,201,204):
                    topology_status,_=rest_put(rest_port,translated,3)
                if topology_status in (200,201,204):
                    topology_operational_status,operational=rest_get_operational(rest_port,3)
                    if topology_operational_status==200 and GRAPH_NAME.encode() in operational:
                        time.sleep(1); break
            time.sleep(1)
        else: raise RuntimeError("OpenDaylight RESTCONF/graph did not become ready")
        sock=establish("127.0.0.1",pcep_port,30); sequence=0
        def execute(request,measured):
            nonlocal sequence
            sequence+=1; wire=request_message(sequence,ip_by_node[request["source"]],ip_by_node[request["target"]],
              request.get("constraints",{}).get("min_bandwidth_bps"))
            before=time.monotonic_ns(); sock.sendall(wire)
            while True:
                kind,reply=recv_message(sock,30)
                if kind==2: continue  # PCEP keepalives can interleave with replies.
                if kind!=4:
                    raise RuntimeError(f"expected PCRep, received PCEP message type {kind}, payload={reply.hex()}")
                break
            after=time.monotonic_ns()
            response_status,path=parse_reply(reply,ip_by_node[request["source"]],node_by_ip,sequence)
            normalized={"request_id":request["request_id"],"subject":args.subject,"status":response_status}
            if path is not None: normalized.update(path=path,reported_cost=len(path)-1)
            raw.append({"request_id":request["request_id"],"measured":measured,"request_base64":base64.b64encode(wire).decode(),
              "response_base64":base64.b64encode(reply).decode(),"request_sha256":hashlib.sha256(wire).hexdigest(),
              "response_sha256":hashlib.sha256(reply).hexdigest()})
            if measured:
                responses.append(normalized); timings.append({"request_id":request["request_id"],"started_monotonic_ns":before,
                  "ended_monotonic_ns":after,"elapsed_ns":after-before})
        for row in json.loads(args.warmup_requests.read_text()): execute(row,False)
        sampler=sampler_control.start(name,args.output)
        try:
            for row in json.loads(args.requests.read_text()): execute(row,True)
        finally:
            resources=sampler_control.stop(sampler,args.output); sampler=None
        sock.close(); (args.output/"container-inspection.json").write_text(run("docker","inspect",name).stdout)
        logs=run("docker","logs",name,check=False); (args.output/"controller.log").write_text(logs.stdout+logs.stderr)
        write(args.output/"normalized-responses.json",responses); write(args.output/"timing-events.json",timings)
        write(args.output/"adapter-inputs.json",{"image":image,"bgpcep_commit":PINNED_ODL_COMMIT,"container_id":container_id,
          "translated_topology":topology_path.name,"topology_load_status":topology_status,
          "graph_seed_status":graph_seed_status,
          "topology_operational_status":topology_operational_status,
          "runtime_boot_features":list(PINNED_BOOT_FEATURES)})
        write(args.output/"adapter-evidence.json",{"attempts":raw.count,"timing":{"requests":len(timings),
          "elapsed_ns":time.monotonic_ns()-started},"resources":resources,"image_inspection":inspection,
          "transport":{"topology":"RESTCONF graph YANG configuration","requests":"PCEP PCReq/PCRep"}})
    finally:
        raw.close()
        sampler_control.abort(sampler)
        if container_id:
            if not (args.output/"container-inspection.json").exists():
                (args.output/"container-inspection.json").write_text(run("docker","inspect",name,check=False).stdout)
            if not (args.output/"controller.log").exists():
                failure_logs=run("docker","logs",name,check=False)
                (args.output/"controller.log").write_text(failure_logs.stdout+failure_logs.stderr)
        run("docker","rm","-f",name,check=False)
    return 0
if __name__=="__main__":
    try: raise SystemExit(main())
    except (OSError,ValueError,RuntimeError,subprocess.CalledProcessError,json.JSONDecodeError) as error:
        print(f"ODL ADAPTER FAIL: {error}",file=sys.stderr); raise SystemExit(2)
