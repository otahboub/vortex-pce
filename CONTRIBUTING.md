# 🤝 Contributing to VortexPCE

Thank you for your interest in contributing to **VortexPCE**! We welcome contributions from network architects, researchers, software engineers, and eBPF kernel developers.

---

## 🚀 Development Setup & Build Instructions

### Prerequisites
* **Java Development Kit (JDK 17+)**
* **Python 3.10+**
* **Clang / LLVM (for eBPF kernel compilation)**
* **Docker & Docker Compose**

### 1-Line Build & Run
```bash
bash install.sh
```

### Run Unit Test Suite
```bash
mvn -B clean verify
python3 -m unittest discover -v
VORTEX_API_KEY=local-check docker compose config --quiet
```

---

## 🛠️ Contribution Guidelines

1. **Fork the Repository:** Create a feature branch off `main`.
2. **Coding Standards:** Follow standard Java 17 and PEP 8 conventions.
3. **Unit Tests:** Ensure the Maven and Python suites pass and add focused regression tests for new behavior.
4. **Submit Pull Request:** Provide a detailed description of changes, architectural impact, and verification output.

---

## 📜 Code of Conduct

Please maintain respectful, open, and collaborative communication at all times.
