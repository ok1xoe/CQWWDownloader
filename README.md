# CQWW Log Downloader (Spring Boot, Java 21, Virtual Threads)

Stahuje všechny `.log` soubory z adresářové stránky (např. https://cqww.com/publiclogs/2024ph/) do zvoleného adresáře.
Využívá **virtuální vlákna (Project Loom)** pro velmi lehké paralelní spouštění úloh.

## Import do IntelliJ IDEA
1. `File -> New -> Project from Existing Sources...`
2. Vyber složku projektu.
3. Zvol `Maven` a potvrď (IDEA detekuje `pom.xml`).
4. Nastav **JDK 21** jako Project SDK.
5. Spusť `cz.ok1xoe.cqww.CqwwLogDownloaderApplication`.

## Spuštění z příkazové řádky
```bash
mvn -q -DskipTests package
java -jar target/cqww-log-downloader-1.3.0-java21-virtual.jar --out ./logs
```

## Argumenty
- `--url` – zdrojová stránka s odkazy na `.log` (výchozí 2024ph)
- `--out` – cílový adresář (výchozí aktuální)
- `--maxConcurrent` – limit současně aktivních stahování (výchozí 100), slušnost vůči serveru
- `--retries` – počet retry pokusů na jeden soubor (výchozí 3)

## Poznámky
- Není potřeba `--enable-preview`; virtuální vlákna v JDK 21 jsou standardní.
- Barevný výstup (OK/ERR) na konzoli, podrobné logy do `./logs/cqww-downloader.log`.
