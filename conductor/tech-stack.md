# Phone MCP — Tech Stack

- **Language:** Kotlin 2.2.0 (JVM target 17)
- **UI Framework:** Jetpack Compose + Material 3 (BOM 2024.12.01)
- **Architecture:** MVVM + Clean Architecture / Layer Discipline (`mcp/`, `domain/`, `data/`, `service/`, `presentation/`)
- **Dependency Injection:** Hilt / Dagger 2.57.2
- **Network Server:** Ktor Server Netty 3.6.0 with TLS
- **Security:** AndroidX Security Crypto (EncryptedSharedPreferences), self-signed X.509 cert
- **Build System:** Gradle 8.7 + Android Gradle Plugin 8.6.0
- **Testing:** JUnit4, MockK, Compose Testing (`ui-test-junit4`)
