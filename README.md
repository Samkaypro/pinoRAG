# pinoRAG

[![ci](https://github.com/Samkaypro/pinoRAG/actions/workflows/ci.yml/badge.svg)](https://github.com/Samkaypro/pinoRAG/actions/workflows/ci.yml)
[![codeql](https://github.com/Samkaypro/pinoRAG/actions/workflows/codeql.yml/badge.svg)](https://github.com/Samkaypro/pinoRAG/actions/workflows/codeql.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![java](https://img.shields.io/badge/java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![spring-boot](https://img.shields.io/badge/spring--boot-4.0.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![ghcr](https://img.shields.io/badge/ghcr.io-pinorag-blueviolet)](https://github.com/Samkaypro/pinoRAG/pkgs/container/pinorag)

**pinoRAG** is an open-source, multi-tenant, citation-backed RAG API server built on Spring Boot 4 and Spring AI.

Drop it in front of your documents, get back a streaming, cited answer. Bring your own embedder, vector store, and LLM through small Java SPIs.

---

## Why pinoRAG

- **Multi-tenant out of the box.** Every collection, document, chunk, and vector query is scoped to a tenant; the boundary is enforced in the data layer, not the controller.
- **Hybrid retrieval, not vibes.** Vector search plus Postgres full-text via Reciprocal Rank Fusion. Better recall, same latency budget.
- **Citation-backed answers.** Every response returns the chunks that produced it: document, page, score.
- **Pluggable everything.** Swap embedder, vector store, or LLM with one YAML key. SPIs ship for OpenAI, Vertex AI, and Ollama.
- **Self-hostable on day one.** Single Docker Compose brings up Postgres + pgvector + Ollama + the app.
- **OpenTelemetry native.** Spans on every retrieval step, Micrometer metrics on every latency-sensitive op.
- **Apache-2.0 licensed.** Use it in commercial products.

---

## 60-second quickstart (Docker)

```bash
git clone https://github.com/Samkaypro/pinoRAG.git
cd pinoRAG
cp .env.example .env
docker compose up --build
```

When the stack is up:

- App: [http://localhost:8080](http://localhost:8080)
- Health: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- Postgres: `localhost:5432` (db `pinorag`, user `pinorag`)
- Ollama: [http://localhost:11434](http://localhost:11434)

To stop everything and wipe data:

```bash
docker compose down -v
```

> The first boot pulls Postgres (`pgvector/pgvector:pg16`) and Ollama (`ollama/ollama:latest`). Expect 1 to 3 minutes on a fresh machine.

---

## Run from source (without Docker)

You need Java 21 and a running Postgres with `pgvector` enabled.

```bash
./mvnw -B -ntp verify
./mvnw spring-boot:run
```

Override datasource config via env vars:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/pinorag \
SPRING_DATASOURCE_USERNAME=pinorag \
SPRING_DATASOURCE_PASSWORD=pinorag \
./mvnw spring-boot:run
```

---

## Tech stack

| Layer         | Choice                                                   |
| ------------- | -------------------------------------------------------- |
| Runtime       | Java 21                                                  |
| Framework     | Spring Boot 4.0.6, Spring AI 2.0.0-M8                    |
| Build         | Maven (wrapper bundled)                                  |
| Database      | Postgres + pgvector                                      |
| Migrations    | Flyway                                                   |
| Vector store  | pgvector (default), Pinecone (optional)                  |
| Embeddings    | OpenAI, Vertex AI, Ollama                                |
| LLM           | OpenAI, Ollama (Vertex Gemini deferred)                  |
| Doc parsing   | Apache Tika, PDFBox                                      |
| Auth          | API key or JWT                                           |
| Rate limits   | Bucket4j                                                 |
| Observability | Micrometer Tracing + OpenTelemetry, Spring Boot Actuator |

---

## Security

Found a vulnerability? See [`SECURITY.md`](SECURITY.md). Do not file a public issue.

---

## License

Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
