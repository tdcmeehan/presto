# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Presto is a distributed SQL query engine for big data analytics, written primarily in Java with a C++ native execution engine. It uses a plugin-based architecture for extensibility.

## Essential Build Commands

```bash
# Full build
./mvnw clean install

# Build without tests (faster)
./mvnw clean install -DskipTests

# Build without UI
./mvnw clean install -DskipUI

# Check code style
./mvnw checkstyle:check
```

## Running Tests

```bash
# Run all tests in a module
./mvnw test -pl module-name

# Run a specific test class
./mvnw test -pl module-name -Dtest=TestClassName

# Run a specific test method
./mvnw test -pl module-name -Dtest=TestClassName#testMethodName

# Example: Running a native execution test
./mvnw test -pl presto-native-execution -Dtest=com.facebook.presto.nativeworker.TestPrestoContainerBasicQueries
```

To skip all checks, add this flag to Maven commands: `-Dair.check.skip-all=true`.

## Project Architecture

### Core Structure
- **presto-main**: Main server implementation containing the Presto coordinator/worker
- **presto-main-base**: Base components and core functionality shared across the system
- **presto-spi**: Service Provider Interface defining plugin APIs for connectors, functions, and other extensions
- **presto-parser**: SQL parser implementation
- **presto-analyzer**: Query analysis and semantic validation

### Plugin Architecture
Presto uses a plugin-based architecture. Key extension points:
- **Connectors**: Data source integrations (presto-hive, presto-iceberg, presto-delta, presto-kafka, etc.)
- **Functions**: Custom scalar and aggregate functions
- **System Access Control**: Security plugins for authorization
- **Resource Groups**: Resource management plugins

### Distributed Execution Model
- **Coordinator**: Receives queries, performs planning, and orchestrates execution
- **Workers**: Execute query fragments in parallel
- **Exchange**: Data transfer mechanism between stages
- **Split**: Unit of data processing assigned to workers

### Key Classes and Concepts
- **SqlQueryExecution**: Main query execution coordinator
- **PlanOptimizers**: Query optimization rules and transformations
- **ConnectorMetadata**: Interface for connector-specific metadata operations
- **Page**: In-memory columnar data structure for processing
- **Block**: Column data within a Page

### Testing Infrastructure
- Unit tests use TestNG framework
- Integration tests in presto-tests module
- Product tests in presto-product-tests use Docker containers
- Benchmark tests in presto-benchmark for performance validation

## Development Requirements

- Java 8 Update 151+ (64-bit)
- Maven 3.6.3+
- Memory: 4GB+ for product tests
- OS: Linux or Mac OS X

## Code Style Guidelines

### General Style
- Maximum line length: 180 characters
- Follow checkstyle rules in src/checkstyle/presto-checks.xml
- IntelliJ code style template available in the [codestyle](https://github.com/airlift/codestyle) repository

### Naming and Formatting
- Avoid abbreviations in all names — variables, methods, fields, parameters (e.g., `positionCount` instead of `positionCnt`, `dynamicFilterService` instead of `dfs`, `queryId` instead of `qid`)
- Function declarations > 180 chars: one parameter per line, starting on new line after function name
- Group logical units with single empty lines between sections

### Static Imports
- Use static imports for common utilities:
  - `format()` instead of `String.format()`
  - `toImmutableList()` instead of `Collectors.toList()`
  - `NANOSECONDS` instead of `TimeUnit.NANOSECONDS`
  - Common imports: `requireNonNull`, `checkArgument`, `checkState`

### Class Structure
- Order: Fields before methods
- Access level order (descending): public, protected, package-private, private
- Field order: static final, final, normal
- Methods with same access level ordered by call sequence

### Imports
- **NEVER use fully-qualified class names inline in code** (e.g., `new org.apache.iceberg.DeleteFile[0]` or `com.facebook.presto.common.predicate.Range.range(...)`). Always add a proper `import` statement and use the short class name.
- After writing or modifying code, verify that all class references use imports rather than FQNs. Fully-qualified names are only acceptable in Javadoc `{@link ...}` references when needed to disambiguate.

### Best Practices
- Prefer immutable collections (Guava's `ImmutableList`, `ImmutableMap`, etc.)
- Make fields final whenever possible
- Use `Optional` for public method parameters (except performance-critical paths)
- Validate constructor arguments with `requireNonNull` and `checkArgument`
- Use `@Nullable`, `@VisibleForTesting`, `@Override` annotations appropriately

### Comments
- Javadoc (`/** */`) for all interface methods and important public methods
- Use `//` style comments for difficult parts within method bodies
- Document parameters and return values in Javadoc

### Testing
- Use TestNG framework
- Avoid `Thread.sleep` - tests should be deterministic
- No random values in tests
- Be careful with instance fields in tests - TestNG doesn't create new objects per test
- Use `@BeforeMethod` to reinitialize fields and `@Test(singleThreaded = true)` when needed

### Plan Assertion Tests
When writing tests that verify query plans using `assertPlan()`:
- **NEVER use `anyTree()`** - always specify the exact plan structure
- Use specific pattern matchers like `project()`, `filter()`, `exchange()`, `tableScan()`, etc.
- If a test is failing because of intermediate nodes, add the correct matchers for those nodes
- Plan assertions should be precise to catch regressions in plan structure

### Commits
- Atomic commits that pass tests independently
- Follow conventional commit message format:
  - Subject line: ≤50 chars, imperative mood, capitalized, no period
  - Body: 72 char wrap, explain what and why
  - Separate subject from body with blank line
- Recommended: ≤1000 lines per commit
- Reference issues: `Resolves: #1234`

## Contribution Workflow

### Before Starting
- Work through the [Getting Started](https://prestodb.io/getting-started/) materials
- Check for existing issues or create a new one
- For large changes, create an [RFC](https://github.com/prestodb/rfcs)
- Join the [Presto Slack](https://communityinviter.com/apps/prestodb/prestodb) for questions

### Pull Request Guidelines
- PRs should be ≤5000 lines (excluding generated files)
- Each PR can have multiple small commits (≤20 recommended)
- Every commit must pass all tests independently
- Follow the PR template provided
- Tests required for all bug fixes and new features
- Get peer review within your organization before submitting

### Code Review Process
- Expect detailed feedback - this helps maintain quality
- Address all feedback before requesting further review
- If no reviewer assigned after 4 days, ask in #dev Slack channel
- Be patient - thorough reviews improve the codebase

### Important Files
- **CONTRIBUTING.md**: Detailed contribution guidelines
- **CODEOWNERS**: Defines module ownership and review requirements
- **ARCHITECTURE.md**: Mission and high-level architecture

## Important Development Reminders

### Always Check Compilation
When adding new code or making changes, always ensure everything compiles:
```bash
# For main code
./mvnw compile -pl module-name

# For test code
./mvnw test-compile -pl module-name

# Quick check without running tests
./mvnw clean install -DskipTests -pl module-name
```

Common compilation issues to watch for:
- Incorrect package imports (e.g., using `com.facebook.presto.execution.QueryId` instead of `com.facebook.presto.spi.QueryId`)
- Missing type parameters for generic classes
- Incorrect method signatures when overriding
- Exception handling mismatches between interface and implementation