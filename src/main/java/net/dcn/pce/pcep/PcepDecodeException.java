package net.dcn.pce.pcep;

/** A PCEP message could not be decoded. Carries no peer-supplied bytes into the message. */
public class PcepDecodeException extends RuntimeException {
    public PcepDecodeException(String message) {
        super(message);
    }
}
