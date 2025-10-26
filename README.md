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
