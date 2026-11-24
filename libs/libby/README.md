# libby

Vendored copy of [AlessioDP/libby](https://github.com/AlessioDP/libby), branch `gradle`, commit `19f6aca` (upstream has no release of this line). MIT, see [`LICENSE`](LICENSE). The plugin uses it to download and load its libraries from Maven Central.

Local changes:

- Removed transitive dependency resolution.
- Removed JSON configuration (`configureFromJSON`, the `configuration` package).
- Relocation tooling: ASM 9.11.1 (upstream 9.10.1), `jar-relocator` 1.7.
- `download.ArtifactDownloader`, also used for the embedded server's libraries: streams to a temporary file while hashing, verifies SHA-256, moves into place atomically, retries 3 times per URL with backoff (`RetryPolicy`), stops on interrupt, and disables URL caches so `jar:` URLs do not pin a replaced plugin jar.
- A cached library is reused only while it matches its checksum; relocated copies are keyed by checksum and relocations.
- `LibraryManager.retainOnly(Collection<Library>)` deletes every other file in the library directory except the relocation tooling.

Build: `./gradlew -p libs/libby build`. The root build includes it as `:libs:libby:libby-*`.
