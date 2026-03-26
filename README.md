# Mule 4 — Embedded Hazelcast Cache Application

## Overview
Embeds an **in-process Hazelcast caching server** inside a MuleSoft 4 application
using the Spring Module. No external cache server installation required.

## Project Structure (Standard Mule 4 Maven Layout)

```
mule-hazelcast-cache-app/
|-- pom.xml                                           # Maven POM
|-- mule-artifact.json                                # Mule packaging metadata
|-- README.md
|
|-- src/
|   |-- main/
|   |   |-- java/                                     # Java source root
|   |   |   `-- com/example/mule/cache/
|   |   |       `-- HazelcastCacheHelper.java         # Cache facade
|   |   |
|   |   |-- mule/                                     # Mule XML configurations
|   |   |   `-- mule-cache-app.xml                    # HTTP flows + cache ops
|   |   |
|   |   `-- resources/                                # Classpath resources
|   |       |-- beans.xml                             # Spring beans (Hazelcast)
|   |       |-- beans-ehcache.xml                     # Alternative: Ehcache
|   |       |-- ehcache.xml                           # Ehcache 3 definitions
|   |       `-- log4j2.xml                            # Runtime logging config
|   |
|   `-- test/
|       |-- java/                                     # Test Java sources
|       |-- mule/                                     # MUnit test suites
|       |   `-- cache-test-suite.xml
|       `-- resources/                                # Test resources
|           `-- log4j2-test.xml                       # Test logging config
```

### Key Layout Rules
- **src/main/mule/**      -> Mule XML flow files (NOT in resources/)
- **src/main/resources/**  -> Spring beans, property files, log4j2
- **src/main/java/**       -> Java source code
- **src/test/mule/**       -> MUnit XML test files
- **src/test/resources/**  -> Test-only config files
- **mule-artifact.json**   -> Project root (next to pom.xml)

## Quick Start

1. Import: File -> Import -> Anypoint Studio Project from File System
2. Build:  Right-click project -> Maven -> Update Project
3. Run:    Right-click project -> Run As -> Mule Application
4. Test:
   ```bash
   curl -X POST "http://localhost:8081/cache?key=greeting" -d "Hello World"
   curl "http://localhost:8081/cache?key=greeting"
   curl -X DELETE "http://localhost:8081/cache?key=greeting"
   ```

## Endpoints

| Method | Path              | Description              |
|--------|-------------------|--------------------------|
| POST   | /cache?key=<k>    | Store value (body=value) |
| GET    | /cache?key=<k>    | Retrieve value           |
| DELETE | /cache?key=<k>    | Evict key                |

## Dependencies
- Mule Runtime 4.4+
- Spring Module 1.3.6
- Java Module 1.2.10
- Hazelcast 5.3.6

## Important: sharedLibraries
The `<sharedLibraries>` section in pom.xml is **critical**. Without it the
Spring Module's isolated classloader cannot see Hazelcast and deployment fails.
