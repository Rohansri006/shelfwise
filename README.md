# ShelfWise

Private library and collection management system. Java OOP project (MUJ).

## Requirements
- JDK 21 (Eclipse Temurin)
- Maven 3.9+ (IntelliJ also has Maven built in)

## Modules
| Module | Contents |
|---|---|
| `core` | Plain Java 17: models, services, storage, exceptions. No JavaFX. Reusable on Android. |
| `desktop` | JavaFX 21 user interface. Depends on `core`. |

## Run from IntelliJ
1. File → Open → select the `shelfwise` folder (the one containing the parent `pom.xml`).
2. Wait for Maven to finish importing.
3. Open `desktop/src/main/java/com/shelfwise/desktop/Launcher.java` and click the green Run arrow.

## Run from Terminal
```bash
mvn install
mvn -f desktop/pom.xml javafx:run
```

## Run tests
```bash
mvn test
```
