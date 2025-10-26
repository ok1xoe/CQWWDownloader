
# CQWW Log Downloader

A high-performance Java application for downloading CQWW contest log files using Java 21 Virtual Threads and Spring Boot.

## Features

- 🚀 **Fast parallel downloads** using Java 21 Virtual Threads
- 📁 **Automatic directory organization** by year and mode (SSB/CW)
- 🔄 **Automatic retry mechanism** with exponential backoff
- 📊 **Progress tracking** with colored console output
- 🎯 **Flexible download modes** - single year or all years at once
- 💾 **Smart file handling** - skip, replace, or save as new
- 📝 **Comprehensive logging** to file and console

## Requirements

- Java 21 or higher
- Maven 3.6+ (for building)

## Build

`bash mvn clean package`



## Parameters

### `--url`
**Description:** URL of the page to download logs from  
**Default:** `https://cqww.com/publiclogs/2024ph/`  
**Examples:**
- `--url=https://cqww.com/publiclogs/` (downloads all years)
- `--url=https://cqww.com/publiclogs/2024ph/` (downloads 2024 SSB only)
- `--url=https://cqww.com/publiclogs/2023cw/` (downloads 2023 CW only)

### `--out`
**Description:** Output directory where logs will be saved  
**Default:** Current working directory  
**Examples:**
- `--out=/home/user/cqww_logs`
- `--out=./downloaded_logs`
- `--out=C:\CQWW\Logs` (Windows)

### `--maxConcurrent`
**Description:** Maximum number of concurrent downloads  
**Default:** `100`  
**Valid range:** 1 or higher  
**Recommendation:** Use lower values (10-20) to avoid server GOAWAY responses  
**Examples:**
- `--maxConcurrent=10`
- `--maxConcurrent=50`

### `--retries`
**Description:** Number of retry attempts for failed downloads  
**Default:** `3`  
**Valid range:** 0 or higher  
**Examples:**
- `--retries=0` (no retries, single attempt only)
- `--retries=5` (up to 5 retry attempts)

### `--overwrite`
**Description:** How to handle existing files  
**Default:** `replace`  
**Valid values:**
- `skip` - Skip downloading if file already exists
- `replace` - Replace existing file with new download
- `new` - Download as new file with `_new` suffix (e.g., `callsign_new.log`)

**Examples:**
- `--overwrite=skip`
- `--overwrite=replace`
- `--overwrite=new`

## Usage Examples

### Download all years from index page

java -jar cqww-log-downloader-1.3.0-java21-virtual.jar --url=https://cqww.com/publiclogs/


**Result:** Creates directories like:
- `2024_CQWWSSB_LOGS/`
- `2024_CQWWCW_LOGS/`
- `2023_CQWWSSB_LOGS/`
- `2023_CQWWCW_LOGS/`
- etc.

### Download specific year (SSB)

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar --url=https://cqww.com/publiclogs/2024ph/`


### Download specific year (CW)

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar --url=https://cqww.com/publiclogs/2024cw/`


### Download to specific directory

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar \
  --url=https://cqww.com/publiclogs/ \
  --out=/home/user/ham_radio/cqww_logs`


### Conservative download (prevent server overload)

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar \
  --url=https://cqww.com/publiclogs/2024ph/ \
  --maxConcurrent=10 \
  --retries=5`


### Skip existing files (resume interrupted download)

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar \
  --url=https://cqww.com/publiclogs/ \
  --overwrite=skip`


### Download with all parameters

```
java -jar cqww-log-downloader-1.3.0-java21-virtual.jar 
  --url=https://cqww.com/publiclogs/ 
  --out=./cqww_logs 
  --maxConcurrent=20 
  --retries=5 
  --overwrite=skip
```


### Windows example

`java -jar cqww-log-downloader-1.3.0-java21-virtual.jar ^
  --url=https://cqww.com/publiclogs/ 
  --out=C:\Users\YourName\Documents\CQWW 
  --maxConcurrent=15 
  --retries=3 
  --overwrite=skip`


## Output

### Console Output

The application provides colored console output (when terminal supports it):

- 🟢 `⬇️ [OK]` - Successfully downloaded file
- 🟡 `⏭️ [SKIP]` - File skipped (already exists)
- 🔴 `❌ [ERR]` - Download error

### Log File

Detailed logs are saved to: `./logs/cqww-downloader.log`

Log rotation:
- Maximum file size: 10 MB
- Maximum history: 30 days
- Total size cap: 1 GB

### Statistics

At the end of each category download, summary statistics are displayed:

DONE | successful: 1523 skipped: 42 failed: 3 total: 15728640B


## Directory Structure

### When downloading all years from index

```
output_directory/
├── 2024_CQWWSSB_LOGS/
│   ├── callsign1.log
│   ├── callsign2.log
│   └── ...
├── 2024_CQWWCW_LOGS/
│   ├── callsign1.log
│   ├── callsign2.log
│   └── ...
├── 2023_CQWWSSB_LOGS/
│   └── ...
└── 2023_CQWWCW_LOGS/
    └── ...
```


### When downloading single year

```
output_directory/
├── callsign1.log
├── callsign2.log
├── callsign3.log
└── ...
```


## Troubleshooting

### "GOAWAY received" error

**Cause:** Server is limiting concurrent connections.  
**Solution:** Reduce `--maxConcurrent` parameter:

java -jar app.jar --url=... --maxConcurrent=10


### Slow downloads

**Cause:** Network issues or server throttling.  
**Solution:**
- Increase `--retries` parameter
- Reduce `--maxConcurrent` parameter
- Check your internet connection

### Out of memory

**Cause:** Too many concurrent downloads.  
**Solution:** Reduce `--maxConcurrent` or increase Java heap size:

java -Xmx2G -jar app.jar --url=... --maxConcurrent=50


### Permission denied

**Cause:** No write permissions to output directory.  
**Solution:**
- Use `--out` parameter to specify writable directory
- Check directory permissions

chmod +w /path/to/output


## Performance Tips

1. **For stable downloads:** Use `--maxConcurrent=10-20`
2. **For maximum speed:** Use `--maxConcurrent=50-100` (may trigger server limits)
3. **Resume interrupted downloads:** Use `--overwrite=skip`
4. **Reliable downloads:** Use `--retries=5` or higher

## Technical Details

- **Language:** Java 21
- **Framework:** Spring Boot 3.3.4
- **Concurrency:** Java Virtual Threads (Project Loom)
- **HTTP Client:** Java 11+ HttpClient
- **HTML Parser:** Jsoup 1.18.1
- **Logging:** SLF4J + Logback

## License

This is an open-source project. Feel free to use and modify.

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests.

## Author

Created for ham radio contest log management and CQWW contest log downloads.

---

**73!** 
