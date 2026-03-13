# ZenithDB — PostgreSQL-Inspired Database Engine

> A from-scratch, industry-grade relational database engine built in Java 21
> for advanced learning purposes. Every layer — storage, buffer pool, WAL,
> transactions, query processing, and indexing — is implemented without any
> external database libraries.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.6%2B-red.svg)](https://maven.apache.org/)
[![Build](https://img.shields.io/badge/Build-Maven_Multi--Module-green.svg)]()
[![Status](https://img.shields.io/badge/Status-In_Development-yellow.svg)]()

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Module Structure](#module-structure)
- [Project File Structure](#project-file-structure)
- [Build Sequence](#build-sequence)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [PostgreSQL Parallels](#postgresql-parallels)
- [Java Concepts Covered](#java-concepts-covered)
- [Sections Roadmap](#sections-roadmap)
- [OS Compatibility](#os-compatibility)
- [License](#license)

---

## Overview

JavaDB is a PostgreSQL-inspired relational database engine built entirely from
scratch in Java 21. It is designed as a deep-learning project to understand how
real production databases work at every layer — from raw byte I/O on disk all
the way up to SQL parsing and query execution.

**What makes this different from tutorials:**

- Every component mirrors a real PostgreSQL subsystem (with source file references)
- No external database libraries — pure Java only
- Industry-grade code with production-quality comments explaining every design decision
- Full concurrency support using Java 21 features (Virtual Threads, Structured Concurrency)
- Bottom-to-top build order — each section compiles and tests before moving to the next

**What you learn by building this:**

- How databases store data in fixed-size pages on disk
- How a buffer pool works and why it is the most critical performance component
- How Write-Ahead Logging (WAL) guarantees durability without slowing down writes
- How MVCC and 2PL implement transaction isolation
- How a B+Tree index is built on top of the buffer pool
- How SQL is parsed, planned, optimized, and executed using the Volcano model
- Advanced Java 21: Virtual Threads, Sealed Classes, Pattern Matching, Records

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   CLIENT LAYER                       │
│          (JDBC Interface / CLI / REST API)           │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│                  QUERY LAYER                         │
│   Lexer → Parser → AST → Logical Plan → Physical    │
│                    Plan (Optimizer)                  │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│               EXECUTION ENGINE                       │
│    Volcano/Iterator Model → Operators Pipeline       │
│    (Scan, Filter, Join, Aggregate, Sort, Project)    │
└──────┬──────────────┬──────────────────┬────────────┘
       │              │                  │
┌──────▼──────┐ ┌─────▼──────┐  ┌───────▼───────────┐
│  TRANSACTION │ │  CATALOG / │  │   STORAGE ENGINE  │
│   MANAGER   │ │  METADATA  │  │                   │
│  (MVCC/2PL) │ │  (Schema)  │  │  Heap / B+Tree /  │
│  WAL / REDO │ │            │  │  LSM-Tree Pages   │
└──────┬──────┘ └─────┬──────┘  └───────┬───────────┘
       │              │                  │
┌──────▼──────────────▼──────────────────▼───────────┐
│              BUFFER POOL MANAGER                     │
│     (Page Cache, LRU/Clock Eviction, Dirty Pages)   │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│                  DISK MANAGER                        │
│        (Page I/O, File Segments, fsync)              │
└─────────────────────────────────────────────────────┘
```

---

## Module Structure

JavaDB is a Maven multi-module project. Each module is independently compiled
and has clearly defined dependencies.

```
javadb-parent
├── core      ← Storage, Buffer Pool, Catalog, WAL, Transactions
├── query     ← Lexer, Parser, AST, Planner, Optimizer, Executor
├── index     ← B+Tree Index, Hash Index
└── server    ← Connection Handler, Protocol, JDBC Driver
```

**Dependency graph:**

```
core ←── query
core ←── index
core ←── server
query ←── server
index ←── server
```

---

## Project File Structure

```
javadb/
├── LICENSE
├── README.md
├── pom.xml                                     ← Root parent POM
│
├── core/
│   ├── pom.xml
│   └── src/main/java/org/javadb/
│       ├── config/
│       │   ├── DatabaseConfig.java             ← Engine-wide constants (PAGE_SIZE, etc.)
│       │   └── ServerConfig.java               ← Host, port, max connections (Builder)
│       │
│       ├── common/
│       │   ├── PageId.java                     ← (fileId, pageNumber) — page address
│       │   ├── RID.java                        ← (PageId, slotIndex) — row address
│       │   ├── LSN.java                        ← Log Sequence Number (WAL position)
│       │   ├── TransactionId.java              ← Transaction identifier
│       │   └── exception/
│       │       ├── DatabaseException.java      ← Root exception + ErrorCode + Severity
│       │       ├── StorageException.java       ← Disk I/O failures
│       │       ├── BufferPoolException.java    ← Page pin/eviction failures
│       │       ├── WALException.java           ← WAL write/read failures
│       │       ├── TransactionException.java   ← Deadlock, lock timeout
│       │       ├── CatalogException.java       ← Schema not found
│       │       └── QueryException.java         ← SQL parse/execute errors
│       │
│       ├── storage/
│       │   ├── disk/
│       │   │   ├── StorageFile.java            ← Enum: DATA, INDEX, WAL, TEMP
│       │   │   ├── FileHandle.java             ← FileChannel wrapper + RW lock
│       │   │   └── DiskManager.java            ← Sole filesystem interface
│       │   ├── page/
│       │   │   ├── Page.java                   ← 8KB ByteBuffer + dirty/pin state
│       │   │   ├── PageHeader.java             ← LSN, checksum, free space pointers
│       │   │   └── SlottedPage.java            ← Slot array + tuple insert/delete
│       │   ├── buffer/
│       │   │   ├── Frame.java                  ← Buffer frame (page + metadata)
│       │   │   ├── PageTable.java              ← ConcurrentHashMap<PageId, Frame>
│       │   │   ├── BufferPoolManager.java      ← Central page cache
│       │   │   └── eviction/
│       │   │       ├── EvictionPolicy.java     ← Strategy interface
│       │   │       ├── ClockEvictor.java       ← Clock hand algorithm
│       │   │       └── LRUEvictor.java         ← Full LRU (doubly linked list)
│       │   ├── heap/
│       │   │   ├── HeapFile.java               ← Table = linked list of pages
│       │   │   └── FreeSpaceMap.java           ← Tracks free space per page
│       │   └── tuple/
│       │       ├── Tuple.java                  ← A single row
│       │       ├── TupleDescriptor.java        ← Schema of a tuple
│       │       ├── NullBitmap.java             ← NULL tracking per tuple
│       │       └── TupleSerializer.java        ← byte[] ↔ Tuple conversion
│       │
│       ├── types/
│       │   ├── DataType.java                   ← Enum: INTEGER, BIGINT, VARCHAR...
│       │   ├── TypeDescriptor.java             ← Type + length (e.g. VARCHAR(255))
│       │   └── values/
│       │       ├── FieldValue.java             ← Sealed interface for all values
│       │       ├── IntValue.java
│       │       ├── BigIntValue.java
│       │       ├── VarcharValue.java
│       │       ├── BooleanValue.java
│       │       ├── FloatValue.java
│       │       ├── TimestampValue.java
│       │       └── NullValue.java
│       │
│       ├── catalog/
│       │   ├── Catalog.java                    ← Central schema registry
│       │   ├── TableSchema.java                ← Table name + columns + constraints
│       │   ├── ColumnDefinition.java           ← Name, type, nullable, default
│       │   ├── IndexDefinition.java            ← Index name, table, columns, type
│       │   └── system/
│       │       ├── PgClass.java                ← mirrors pg_class
│       │       ├── PgAttribute.java            ← mirrors pg_attribute
│       │       └── PgIndex.java                ← mirrors pg_index
│       │
│       ├── wal/
│       │   ├── WALManager.java                 ← Log buffer, LSN counter, flush
│       │   ├── WALRecord.java                  ← Sealed interface for log records
│       │   ├── WALWriter.java                  ← Serializes records to segments
│       │   ├── WALReader.java                  ← Replays log during recovery
│       │   └── records/
│       │       ├── BeginRecord.java
│       │       ├── CommitRecord.java
│       │       ├── AbortRecord.java
│       │       ├── UpdateRecord.java           ← Before + After image
│       │       ├── InsertRecord.java
│       │       ├── DeleteRecord.java
│       │       └── CheckpointRecord.java
│       │
│       ├── transaction/
│       │   ├── TransactionManager.java         ← Begin/Commit/Abort
│       │   ├── Transaction.java                ← Tx state + lock list
│       │   ├── IsolationLevel.java             ← READ_COMMITTED, REPEATABLE_READ...
│       │   ├── lock/
│       │   │   ├── LockManager.java            ← Lock table, grant/wait/release
│       │   │   ├── LockRequest.java            ← (txId, resource, mode, status)
│       │   │   ├── LockMode.java               ← SHARED, EXCLUSIVE, IS, IX, SIX
│       │   │   ├── LockTable.java              ← Per-resource lock queues
│       │   │   └── DeadlockDetector.java       ← Wait-for graph cycle detection
│       │   └── mvcc/
│       │       ├── VersionChain.java           ← Tuple version linked list
│       │       └── VisibilityChecker.java      ← MVCC snapshot visibility
│       │
│       └── recovery/
│           ├── RecoveryManager.java            ← ARIES: Analysis → Redo → Undo
│           ├── DirtyPageTable.java             ← Pages modified but not flushed
│           └── TransactionTable.java           ← Active txs at crash time
│
├── query/
│   └── src/main/java/org/javadb/
│       ├── sql/
│       │   ├── lexer/
│       │   │   ├── Lexer.java                  ← Character stream → Token stream
│       │   │   ├── Token.java                  ← (type, value, line, col)
│       │   │   └── TokenType.java              ← SELECT, FROM, WHERE, INSERT...
│       │   ├── parser/
│       │   │   ├── Parser.java                 ← Recursive descent parser
│       │   │   └── ParseException.java
│       │   └── ast/
│       │       ├── Statement.java              ← Sealed interface
│       │       ├── SelectStatement.java
│       │       ├── InsertStatement.java
│       │       ├── UpdateStatement.java
│       │       ├── DeleteStatement.java
│       │       ├── CreateTableStatement.java
│       │       └── expr/
│       │           ├── Expression.java         ← Sealed interface
│       │           ├── BinaryExpression.java
│       │           ├── ColumnRef.java
│       │           ├── Literal.java
│       │           └── FunctionCall.java
│       ├── planner/
│       │   ├── LogicalPlanner.java             ← AST → Logical Plan
│       │   ├── PhysicalPlanner.java            ← Logical → Physical Plan
│       │   ├── logical/
│       │   │   ├── LogicalScan.java
│       │   │   ├── LogicalFilter.java
│       │   │   ├── LogicalJoin.java
│       │   │   └── LogicalAggregate.java
│       │   └── optimizer/
│       │       ├── Optimizer.java
│       │       └── rules/
│       │           ├── PredicatePushdown.java
│       │           ├── ProjectionPruning.java
│       │           └── JoinReorder.java
│       └── executor/
│           ├── ExecutionEngine.java            ← Orchestrates operator tree
│           ├── ExecutionContext.java           ← Tx + catalog + buffer pool ref
│           ├── ResultSet.java                  ← Iterator over result tuples
│           └── operators/
│               ├── Operator.java               ← open() / next() / close()
│               ├── SeqScanOperator.java
│               ├── IndexScanOperator.java
│               ├── FilterOperator.java
│               ├── HashJoinOperator.java
│               ├── HashAggregateOperator.java
│               ├── SortOperator.java
│               └── LimitOperator.java
│
├── index/
│   └── src/main/java/org/javadb/
│       └── index/
│           ├── Index.java                      ← Interface: insert/delete/search
│           ├── btree/
│           │   ├── BTreeIndex.java
│           │   ├── BTreeNode.java              ← Page-resident node
│           │   ├── BTreeInternalNode.java
│           │   ├── BTreeLeafNode.java
│           │   └── BTreeIterator.java          ← Range scan
│           └── hash/
│               ├── HashIndex.java
│               └── HashBucket.java
│
└── server/
    └── src/main/java/org/javadb/
        ├── server/
        │   ├── DatabaseServer.java             ← Main entry, virtual thread/conn
        │   ├── ConnectionHandler.java          ← Per-connection lifecycle
        │   └── Session.java                    ← Session state
        ├── protocol/
        │   ├── PostgresWireProtocol.java       ← pg wire protocol v3
        │   └── SimpleProtocol.java             ← Line-based protocol
        └── jdbc/
            ├── JavaDbDriver.java               ← JDBC Driver registration
            ├── JavaDbConnection.java           ← JDBC Connection
            ├── JavaDbStatement.java            ← JDBC Statement
            └── JavaDbResultSet.java            ← JDBC ResultSet
```

---

## Build Sequence

The project is built strictly **bottom-to-top**. Each section must compile and
pass tests before moving to the next. This mirrors how PostgreSQL itself is
structured — the storage layer knows nothing about SQL.

```
Section 01 — DatabaseConfig & ServerConfig
Section 02 — Common Types (PageId, RID, LSN, TransactionId)
Section 03 — Exception Hierarchy
Section 04 — DiskManager (FileChannel, CRC32, fsync)
Section 05 — Page & PageHeader (8KB ByteBuffer, slotted layout)
Section 06 — SlottedPage (slot array, tuple insert/delete)
Section 07 — Buffer Pool Frame & PageTable
Section 08 — Eviction Algorithms (Clock, LRU)
Section 09 — BufferPoolManager (full implementation)
Section 10 — Type System (DataType, FieldValue sealed hierarchy)
Section 11 — Tuple, NullBitmap, TupleSerializer
Section 12 — HeapFile & FreeSpaceMap
Section 13 — Catalog (pg_class, pg_attribute, pg_index mirrors)
Section 14 — WAL Records & WALManager
Section 15 — Transaction & LockManager (2PL)
Section 16 — DeadlockDetector & Recovery (ARIES)
Section 17 — Lexer & Token types
Section 18 — Parser & AST nodes
Section 19 — Logical Planner & Optimizer
Section 20 — Execution Engine & Operators (Volcano model)
Section 21 — B+Tree Index (page-resident)
Section 22 — Server & Connection Handler (Virtual Threads)
Section 23 — JDBC Driver layer
```

---

## Prerequisites

| Tool  | Version | Notes                                      |
|-------|---------|--------------------------------------------|
| Java  | 21 LTS  | Virtual threads, sealed classes, records   |
| Maven | 3.6+    | Multi-module build support                 |
| OS    | Any     | Linux, Windows 10/11, macOS (NIO Path API) |

Verify your environment:

```bash
java --version   # must show 21+
mvn -version     # must show 3.6+
```

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/your-username/javadb.git
cd javadb
```

### 2. Build all modules

```bash
mvn clean install -DskipTests
```

### 3. Build and run tests

```bash
mvn clean install
```

### 4. Build a specific module only

```bash
# Build core and all its dependencies
mvn clean install -pl core --am

# Build query module
mvn clean install -pl query --am
```

### 5. Run the database server (Section 22+)

```bash
cd server
mvn exec:java -Dexec.mainClass="org.javadb.server.DatabaseServer"
```

---

## PostgreSQL Parallels

Every component in JavaDB has a direct equivalent in the PostgreSQL source.

| JavaDB Component        | PostgreSQL Equivalent              | Source File            |
|-------------------------|------------------------------------|------------------------|
| `DatabaseConfig`        | `pg_config_manual.h`               | backend/include/       |
| `DiskManager`           | Storage Manager (`smgr`)           | smgr/smgr.c, md.c      |
| `BufferPoolManager`     | Buffer Manager                     | storage/buffer/bufmgr.c|
| `Page` / `PageHeader`   | `PageHeaderData`                   | storage/bufpage.h      |
| `SlottedPage`           | Heap page layout                   | storage/bufpage.c      |
| `HeapFile`              | Heap file manager                  | access/heap/heapam.c   |
| `Catalog`               | System catalog                     | catalog/pg_class.h     |
| `WALManager`            | WAL writer                         | access/transam/xlog.c  |
| `TransactionManager`    | Transaction manager                | access/transam/xact.c  |
| `LockManager`           | Lock manager                       | storage/lmgr/lock.c    |
| `RecoveryManager`       | Startup / ARIES recovery           | postmaster/startup.c   |
| `Lexer` / `Parser`      | Grammar / scanner                  | parser/gram.y, scan.l  |
| `BTreeIndex`            | `nbtree` access method             | access/nbtree/         |
| `DatabaseServer`        | Postmaster                         | postmaster/postmaster.c|

---

## Java Concepts Covered

### Core Language
- `record` classes — immutable value objects (PageId, RID, LSN)
- `sealed interface` + `permits` — algebraic types (FieldValue, WALRecord, AST)
- Pattern matching `switch` — type dispatch in query execution
- Generics + bounded type parameters — `BTreeNode<K extends Comparable<K>>`
- `enum` with fields and methods — DataType, LockMode, TokenType

### Memory & I/O
- `ByteBuffer` (heap + direct) — page read/write without object overhead
- `FileChannel` — random-access page I/O (position-based reads)
- `MappedByteBuffer` — memory-mapped WAL segment files
- `CRC32` — page integrity checksums
- `StandardOpenOption` — type-safe file open modes

### Concurrency
- `ReentrantReadWriteLock` — per-frame buffer pool locking
- `StampedLock` — optimistic reads in hot path
- `ConcurrentHashMap` — lock-free page table
- `AtomicLong` / `AtomicInteger` — LSN counter, pin count
- `ThreadLocal` — per-transaction context propagation
- `BlockingQueue` — WAL log buffer (producer-consumer)
- Virtual Threads (`Thread.ofVirtual()`) — one thread per connection
- `ScheduledExecutorService` — checkpoint + WAL flush daemons
- Structured Concurrency — coordinated WAL flush + checkpoint

### Design Patterns
- Strategy — `EvictionPolicy` (swap Clock ↔ LRU)
- Iterator — Volcano model (`Operator.next()`)
- Composite — operator tree (each op wraps child ops)
- Builder — `ServerConfig.builder()`, `TableSchema.builder()`
- Command — WAL log records (redo/undo operations)
- Template Method — `AbstractOperator` lifecycle

---

## Sections Roadmap

| Section | Component                      
|---------|--------------------------------
| 01      | DatabaseConfig & ServerConfig  
| 02      | Common Types                   
| 03      | Exception Hierarchy            
| 04      | DiskManager                   
| 05      | Page & PageHeader              
| 06      | SlottedPage                    
| 07      | Buffer Pool Frame & PageTable  
| 08      | Eviction Algorithms            
| 09      | BufferPoolManager              
| 10      | Type System                    
| 11      | Tuple & Serializer             
| 12      | HeapFile & FreeSpaceMap       
| 13      | Catalog                       
| 14      | WAL Manager                    
| 15      | Transaction & Lock Manager     
| 16      | Deadlock & Recovery (ARIES)  
| 17      | Lexer & Tokens                
| 18      | Parser & AST                   
| 19      | Logical Planner & Optimizer    
| 20      | Execution Engine & Operators   
| 21      | B+Tree Index                   
| 22      | Server & Connection Handler   
| 23      | JDBC Driver                  

---

## OS Compatibility

JavaDB is fully OS-compatible using Java's NIO Path API throughout.

| Feature                  | Linux | Windows 10/11 | macOS |
|--------------------------|-------|---------------|-------|
| File I/O (NIO Path)      | ✅    | ✅            | ✅    |
| Path separators          | ✅    | ✅            | ✅    |
| `FileChannel.force()`    | ✅    | ✅            | ✅    |
| `Files.createDirectories`| ✅    | ✅            | ✅    |
| Delete open files        | ✅    | ✅ (close first)| ✅  |
| Virtual Threads          | ✅    | ✅            | ✅    |
| CRC32 checksums          | ✅    | ✅            | ✅    |

**Path handling rule:** The codebase uses `Path.resolve()` and
`Paths.get()` exclusively — never string concatenation with `/` or `\`.

---

## License

This project is licensed under the MIT License.
See the [LICENSE](LICENSE) file for full details.

```
MIT License — Copyright (c) 2026 JavaDB Contributors

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software to use, copy, modify, merge, publish, and
distribute it, subject to including the above copyright notice in all
copies or substantial portions of the Software.
```

---

---

*Built for learning. Every line commented. Every decision explained.*
