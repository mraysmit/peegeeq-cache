# Consolidated Guidelines

This document consolidates all markdown files currently in docs/guidelines in source-file order.



---

## Source: docs/guidelines/README.md


# Guidelines Index

This folder currently contains a mix of:

- local guidance adopted for `peegee-cache`
- imported reference material copied from the sibling `peegeeq` repository

Use the files with that distinction in mind.

## Local-first guidance

- `pgq-coding-principles.md`: primary coding and testing guidance carried into this repo
- `VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md`: imported postmortem adapted with tenant-isolation notes for this repo

## Imported reference material

- `Vertx-5x-Patterns-Guide.md`: style and architecture reference from sibling PeeGeeQ modules
- `MAVEN_TOOLCHAINS_EXPLAINED.md`: generic Maven toolchains explanation; examples are illustrative, not machine-specific requirements
- `PEEGEEQ_CRASH_RECOVERY_GUIDE.md`: sibling-repo recovery design reference
- `SHUTDOWN_AND_LIFECYCLE_FIXES.md`: sibling-repo lifecycle postmortem reference

When a document mentions module paths such as `peegeeq-db` or `peegeeq-service-manager`, assume those references are to the sibling repository unless the path also exists in `peegee-cache`.

---

## Source: docs/guidelines/pgq-coding-principles.md


# PeeGeeQ Coding Principles & Standards

Based on refactoring lessons learned, strictly adhere to the following principles.

Imported reference note:

- this document was copied from the sibling `peegeeq` repository as working guidance
- many class names, module names, and file paths below refer to that sibling repository, not to files that exist in `peegee-cache`
- treat those concrete examples as reference patterns unless this repo adds matching modules

## **🚨 CRITICAL: TestContainers Mandatory Policy**

### **Principle: "Database-Centric Systems Require Real Databases"**

**PeeGeeQ is a PostgreSQL queue system. Almost zero operations do not involve the database.**

- ✅ **MANDATORY**: Use TestContainers for ALL tests involving database operations
- ❌ **FORBIDDEN**: Mocking database connections, repositories, or SQL operations  
- ❌ **FORBIDDEN**: Using H2, HSQLDB, or in-memory databases as PostgreSQL substitutes
- ❌ **FORBIDDEN**: Skipping database tests because "TestContainers is slow"

**No Exceptions. No Excuses.**

```java
// ✅ CORRECT: Any database operation uses TestContainers
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerCoreTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
    
    @Test
    void testSendMessage() {
        // Tests real database writes through OutboxProducer
    }
}

// ✅ CORRECT: Pure logic test with NO database
@Tag(TestCategories.CORE)
class CircuitBreakerRecoveryTest {
    // NO @Testcontainers needed - tests pure state machine logic
    // No database operations = no TestContainers required
    
    @Test
    void testStateTransitions() {
        // Tests circuit breaker CLOSED → OPEN → HALF_OPEN logic
    }
}

// ❌ WRONG: Database test without TestContainers
@Tag(TestCategories.CORE)
class SomeTest {
    @Test
    void testDatabaseOperation() {
        OutboxFactory factory = ... // ← This will access database
        factory.createProducer(...); // ← Database operation!
        // MUST use @Tag(TestCategories.INTEGRATION) and @Testcontainers
    }
}
```

**When to Use Each Tag:**
- `@Tag(TestCategories.CORE)`: Pure logic tests with **ZERO** database operations
  - Examples: Circuit breaker logic, filter predicates, error handling paths, config validation
  - Run in default Maven profile for fast feedback
  
- `@Tag(TestCategories.INTEGRATION)`: **ANY** test that touches the database
  - Examples: Producer.send(), Consumer.poll(), Factory creation, message persistence
  - Requires `@Testcontainers` with PostgreSQL container
  - Run with `-Pintegration-tests` profile

**If you're unsure whether your test needs TestContainers, ask:**
1. Does this test create OutboxFactory/OutboxProducer/OutboxConsumer?
2. Does this test send, receive, or query messages?
3. Does this test involve PeeGeeQManager or DatabaseService?

**If YES to any → Use INTEGRATION + TestContainers. No exceptions.**

---

## **Investigation Before Implementation**

### **Principle: "Understand Before You Change"**
- **My Mistake**: I initially added graceful error handling to `SystemPropertiesIntegrationTest` without investigating why it was failing
- **Better Approach**: Always investigate the root cause first
- **Code Practice**:
  ```java
  // BAD: Catching and hiding errors without understanding
  try {
      // database operation
  } catch (Exception e) {
      logger.warn("Expected failure, skipping...");
      return; // Hide the real problem
  }
  
  // GOOD: Investigate and fix the root cause
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
      .withDatabaseName("test_db");
  ```

## **Learn From Existing Patterns**

### **Principle: "Follow Established Conventions"**
- **My Mistake**: I didn't check how other integration tests in the project were structured
- **Better Approach**: Always examine existing patterns before creating new ones
- **Code Practice**:
  ```java
  // Research existing patterns first
  @Testcontainers  // ← Found this pattern in other tests
  class MyIntegrationTest {
      @Container
      static PostgreSQLContainer<?> postgres = // ← Consistent setup
  }
  ```

## **Verify Assumptions**

### **Principle: "Test Your Understanding"**
- **My Mistake**: I assumed the test was "working as intended" without carefully reading the logs
- **Better Approach**: Always verify that tests are actually doing what you think they're doing
- **Code Practice**:
  ```java
  // Don't assume - verify with explicit logging
  @Test
  void testDatabaseConnection() {
      logger.info("Testing with database: {}:{}", 
          postgres.getHost(), postgres.getFirstMappedPort());
      // Explicit verification of what's happening
  }
  ```

## **Precise Problem Identification**

### **Principle: "Fix the Cause, Not the Symptom"**
- **My Mistake**: I treated database connection failures as "expected behavior" instead of missing TestContainers setup
- **Better Approach**: Distinguish between legitimate failures and configuration issues
- **Code Practice**:
  ```java
  // BAD: Masking configuration problems
  if (databaseConnectionFailed) {
      logger.warn("Expected failure in test environment");
      return; // Wrong - this hides real issues
  }
  
  // GOOD: Proper test infrastructure
  @BeforeEach
  void configureDatabase() {
      System.setProperty("db.host", postgres.getHost());
      System.setProperty("db.port", String.valueOf(postgres.getFirstMappedPort()));
  }
  ```

## **Clear Documentation Standards**

### **Principle: "Document Intent, Not Just Implementation"**
- **My Mistake**: I wrote misleading comments about "expected behavior" when the real issue was missing setup
- **Better Approach**: Document the actual purpose and requirements
- **Code Practice**:
  ```java
  /**
   * Integration test that validates system properties with a real database.
   * Uses TestContainers to provide PostgreSQL for testing.
   * 
   * Requirements:
   * - Docker must be available for TestContainers
   * - Test validates actual database connectivity
   */
  @Testcontainers
  class SystemPropertiesIntegrationTest {
  ```

## **Iterative Validation**

### **Principle: "Validate Each Step"**
- **My Mistake**: I made multiple changes without validating each one individually
- **Better Approach**: Make small changes and verify each step works
- **Code Practice**:
  ```java
  // Step 1: Add TestContainers - verify it starts
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");
  
  // Step 2: Configure connection - verify it connects
  System.setProperty("db.host", postgres.getHost());
  
  // Step 3: Test actual functionality - verify it works
  ```

## **Test Classification**

### **Principle: "Clearly Distinguish Test Types"**
- **My Mistake**: I confused integration tests (which should have real infrastructure) with unit tests (which can mock)
- **Better Approach**: Be explicit about what each test requires
- **Code Practice**:
  ```java
  // Unit Test (CORE) - Pure logic tests with NO database operations
  // Use @Tag(TestCategories.CORE) for fast-running tests that test pure logic
  // Examples: Circuit breaker state transitions, filter logic, error handling paths
  @Tag(TestCategories.CORE)
  class CircuitBreakerRecoveryTest {
      // NO @Testcontainers - this tests pure logic, not database operations
      // Use real objects or simple manual stubs, NOT Mockito
      
      @Test
      void testCircuitBreakerTransitions() {
          // Test circuit breaker logic directly without database
      }
  }
  
  // Integration Test (INTEGRATION) - ANY test involving database operations
  // Use @Tag(TestCategories.INTEGRATION) for ALL tests that touch the database
  // MANDATORY: Use TestContainers for real PostgreSQL - NO EXCEPTIONS
  @Tag(TestCategories.INTEGRATION)
  @Testcontainers
  class OutboxProducerCoreTest {
      @Container
      static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15.13-alpine3.20");
      
      @Test
      void testProducerSendsMessage() {
          // Any test that reads/writes database MUST use TestContainers
      }
  }
  
  // STRICT RULES FOR PEEGEEQ (Database-Centric System):
  // 1. NO Mockito or Reflection - EVER
  // 2. ANY test involving database operations MUST use TestContainers
  // 3. TestContainers is NOT optional - this is a database-centric system
  // 4. Only tag tests as CORE if they test pure logic with ZERO database operations
  // 5. When in doubt, use INTEGRATION with TestContainers
  ```

### **TestContainers: Mandatory for Database-Centric Systems**

**CRITICAL**: PeeGeeQ is a **database-centric PostgreSQL queue system**. Almost every operation involves database reads/writes. Therefore:

- ✅ **REQUIRED**: Use TestContainers for ANY test that touches the database
- ❌ **FORBIDDEN**: Mocking database connections, repositories, or SQL operations
- ❌ **FORBIDDEN**: Using H2, HSQLDB, or any in-memory database as PostgreSQL substitutes
- ✅ **CORRECT**: Real PostgreSQL via TestContainers for authentic behavior

**When to Use CORE vs INTEGRATION Tags:**

```java
// ✅ CORE: Pure logic, no database
@Tag(TestCategories.CORE)
class CircuitBreakerLogicTest {
    // Tests state transitions: CLOSED → OPEN → HALF_OPEN
    // No database needed - pure in-memory logic
}

// ✅ CORE: Pure logic, no database
@Tag(TestCategories.CORE)
class FilterValidationTest {
    // Tests message filtering predicates
    // No database needed - pure function logic
}

// ✅ INTEGRATION: Uses database
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxProducerTest {
    @Container
    static PostgreSQLContainer<?> postgres = // REQUIRED
    
    // Tests producer.send() which writes to database
    // Database operation = MUST use TestContainers
}

// ✅ INTEGRATION: Uses database
@Tag(TestCategories.INTEGRATION)
@Testcontainers
class OutboxConsumerTest {
    @Container
    static PostgreSQLContainer<?> postgres = // REQUIRED
    
    // Tests consumer.poll() which reads from database
    // Database operation = MUST use TestContainers
}

// ❌ WRONG: Database test without TestContainers
@Tag(TestCategories.CORE)  // ← WRONG TAG
class OutboxFactoryTest {
    @Test
    void testCreateProducer() {
        // This creates a producer that will access database
        // MUST be @Tag(TestCategories.INTEGRATION)
        // MUST use @Testcontainers
    }
}
```

**No Excuses Policy:**
- "It's too slow" → Use INTEGRATION profile for CI, CORE for quick feedback
- "TestContainers is complex" → We have established patterns (see OutboxBasicTest)
- "Docker not available" → Fix your development environment
- "Just testing configuration" → If config affects database behavior, use TestContainers

**Summary: If it touches PostgreSQL, it MUST use TestContainers. Period.**

## **Honest Error Handling**

### **Principle: "Fail Fast, Fail Clearly"**
- **My Mistake**: I tried to make tests "pass gracefully" when they should have been fixed to work properly
- **Better Approach**: Let tests fail when there are real problems, fix the problems
- **Code Practice**:
  ```java
  // BAD: Hiding real failures
  try {
      realOperation();
  } catch (Exception e) {
      logger.warn("Skipping due to environment");
      return; // Test "passes" but doesn't test anything
  }
  
  // GOOD: Proper setup so tests can succeed
  @Container
  static PostgreSQLContainer<?> postgres = // Provide real infrastructure
  ```

## **Log Analysis Skills**

### **Principle: "Read Logs Carefully"**
- **My Mistake**: I didn't carefully analyze what the error logs were actually telling me
- **Better Approach**: Parse error messages to understand the real problem
- **Code Practice**:
  ```java
  // When you see: "UnknownHostException: test-host"
  // Don't think: "Expected failure in test environment"
  // Think: "This test needs a real database host"
  
  // Solution: Provide the real host via TestContainers
  System.setProperty("db.host", postgres.getHost());
  ```

## **Modern Vert.x 5.x Composable Future Patterns**

### **Principle: "Use Composable Futures, Not Callbacks"**
- **Requirement**: All asynchronous operations must use Vert.x 5.x composable Future patterns
- **Better Approach**: Use `.compose()` chains instead of nested callbacks for better readability and error handling
- **Code Practice**:
  ```java
  // BAD: Old callback style (avoid)
  server.listen(8080, ar -> {
      if (ar.succeeded()) {
          doWarmup(warmupResult -> {
              if (warmupResult.succeeded()) {
                  registerWithRegistry(registryResult -> {
                      // Callback hell...
                  });
              }
          });
      }
  });

  // GOOD: Modern Vert.x 5.x composable style (required)
  server.listen(8080)
      .compose(s -> doWarmup())           // returns Future<Void>
      .compose(v -> registerWithRegistry()) // returns Future<Void>
      .onSuccess(v -> System.out.println("Server is ready"))
      .onFailure(Throwable::printStackTrace);

  // GOOD: Error recovery with graceful degradation
  primaryOperation()
      .recover(throwable -> {
          logger.warn("Primary failed, using fallback: {}", throwable.getMessage());
          return fallbackOperation();
      })
      .onSuccess(result -> handleResult(result));

  // BAD: Old .onComplete(ar -> { if (ar.succeeded()) ... }) pattern
  queue.send(message)
      .onComplete(ar -> {
          if (ar.succeeded()) {
              latch.countDown();
          } else {
              fail("Failed: " + ar.cause().getMessage());
          }
      });

  // GOOD: Modern .onSuccess()/.onFailure() pattern
  queue.send(message)
      .onSuccess(v -> latch.countDown())
      .onFailure(throwable -> fail("Failed: " + throwable.getMessage()));
  ```

## **Summary: Core Principles**

1. **Investigate First**: Understand the problem before implementing solutions
2. **Follow Patterns**: Learn from existing code in the same project
3. **Verify Assumptions**: Don't assume tests are working - check the logs
4. **Fix Root Causes**: Address configuration issues, don't mask them
5. **Document Honestly**: Write comments that reflect actual behavior
6. **Validate Incrementally**: Test each change before moving to the next
7. **Classify Tests Clearly**: Know whether you're writing unit or integration tests
8. **Fail Honestly**: Let tests fail when there are real problems to fix
9. **Read Logs Carefully**: Error messages usually tell you exactly what's wrong
10. **Use Modern Vert.x 5.x Patterns**: Always use composable Futures (`.compose()`, `.onSuccess()`, `.onFailure()`) instead of callback-style programming

Do not reinvent the wheel as you are to do. Work incrementally and test after each small incremental change. When testing make sure you scan the test logs properly for test errors, do not rely on the exit code as that is largely meaningless.

## **Critical Test Execution Validation**

### **Principle: "Verify Test Methods Are Actually Executing"**
- **Critical Discovery**: Maven can report "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0" even when test methods never execute
- **Root Cause**: TestContainers initialization failures can silently prevent test method execution
- **Detection Method**: Use Maven debug mode (`-X`) and explicit diagnostic logging to verify test method execution

### **Maven Test Profile Configuration (CRITICAL)**

**PeeGeeQ uses Maven profiles to separate fast CORE tests from slower INTEGRATION tests.**

- ❌ **WRONG**: `mvn test` (runs CORE tests only, excludes INTEGRATION)
- ❌ **WRONG**: `mvn test -Dtest=OutboxProducerCoreTest` (test won't run if tagged INTEGRATION)
- ✅ **CORRECT**: `mvn test -Pintegration-tests` (runs all INTEGRATION tests)
- ✅ **CORRECT**: `mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests` (runs specific INTEGRATION test)

**Why This Matters:**
```bash
# This looks like success but NO TESTS RAN
$ mvn test -Dtest=OutboxProducerCoreTest
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS

# This actually runs the test
$ mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Profile Configuration (from pom.xml):**
```xml
<!-- Default profile: CORE tests only (fast) -->
<profile>
    <id>core-tests</id>
    <activation><activeByDefault>true</activeByDefault></activation>
    <properties>
        <test.groups>core</test.groups>
        <test.excludedGroups>integration,performance,slow,flaky</test.excludedGroups>
    </properties>
</profile>

<!-- Integration tests profile: Database tests with TestContainers -->
<profile>
    <id>integration-tests</id>
    <properties>
        <test.groups>integration</test.groups>
        <test.excludedGroups>performance,slow,flaky</test.excludedGroups>
    </properties>
</profile>
```

**Development Workflow:**
```bash
# Quick feedback during development (CORE tests only, < 5 seconds)
mvn test

# Before committing (run INTEGRATION tests with TestContainers)
mvn test -Pintegration-tests

# Run specific integration test
mvn test -Dtest=OutboxProducerCoreTest -Pintegration-tests

# Clean build with full integration tests
mvn clean test -Pintegration-tests

# Get coverage report for integration tests
mvn clean test -Pintegration-tests jacoco:report
```

**Common Pitfalls:**
1. ❌ Creating INTEGRATION test, running `mvn test`, seeing "Tests run: 0", assuming test is broken
   - ✅ Solution: Use `-Pintegration-tests` for any test tagged `@Tag(TestCategories.INTEGRATION)`

2. ❌ Tagging database test as CORE to make it run by default
   - ✅ Solution: Tag it INTEGRATION and use proper profile

3. ❌ Assuming BUILD SUCCESS means tests ran
   - ✅ Solution: Check "Tests run:" count in output
- **Code Practice**:
  ```java
  // ALWAYS add diagnostic logging to verify test execution
  @Test
  void testSomething() {
      System.err.println("=== TEST METHOD STARTED ===");
      System.err.flush();

      // Your test logic here
      assertTrue(actualCondition, "Test assertion");

      System.err.println("=== TEST METHOD COMPLETED ===");
      System.err.flush();
  }

  // Use Maven debug mode to see diagnostic output
  // mvn test -Dtest=YourTest -X
  ```

### **TestContainers Debugging Strategy**
- **Issue**: TestContainers setup can fail silently, preventing test method execution
- **Solution**: Isolate TestContainers issues by temporarily disabling container setup
- **Code Practice**:
  ```java
  // Step 1: Test without TestContainers to verify JUnit execution
  //@Testcontainers  // Comment out temporarily
  public class YourTest {
      //@Container  // Comment out temporarily
      //static PostgreSQLContainer<?> postgres = ...

      @Test
      void testBasicExecution() {
          System.err.println("=== BASIC TEST EXECUTION ===");
          assertTrue(true, "This should always pass");
      }
  }

  // Step 2: Once basic execution is verified, re-enable TestContainers
  // Step 3: Add container-specific diagnostics
  @BeforeEach
  void setUp() {
      System.err.println("=== CONTAINER STATUS: " + postgres.isRunning() + " ===");
      System.err.println("=== JDBC URL: " + postgres.getJdbcUrl() + " ===");
  }
  ```

### **Maven Test Log Analysis**
- **Critical Skill**: Always use Maven debug mode (`-X`) when investigating test issues
- **Key Indicators**: Look for actual test method output, not just Maven summary
- **Warning Signs**:
  - Tests "pass" but no test-specific log output appears
  - Container startup logs appear but test method diagnostics are missing
  - Exit code 0 but expected test behavior doesn't occur



Also, here is an imported **Vert.x 4.x → 5.x migration checklist** from the sibling PeeGeeQ project. Keep using it as reference guidance, but do not assume the module paths and implementation-status statements below describe the current `peegee-cache` repository.

---

# 1. Dependencies & Build Setup

* **Use the Vert.x 5.x BOM (`vertx-dependencies`)**

  ```xml
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-dependencies</artifactId>
        <version>5.0.4</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  ```
* Update all Vert.x dependencies to **5.x** (don’t mix 4.x/5.x).
* **Do not add Netty manually** → Vert.x manages it.
*  Some artifacts were renamed/reorganized:

  * `vertx-rx-java2` → dropped. Use `vertx-rx-java3`.
  * Mutiny wrappers are available under `io.vertx:vertx-mutiny-*`.

---

# 2. API Changes

### Futures Replace Callbacks

* **Vert.x 4.x**:

  ```java
  server.listen(8080, ar -> {
    if (ar.succeeded()) { ... }
  });
  ```
* **Vert.x 5.x**:

  ```java
  server.listen(8080)
        .onSuccess(s -> ...)
        .onFailure(Throwable::printStackTrace);
  ```

### Composition is Cleaner

* `.compose()`, `.map()`, `.recover()` replace `future.setHandler()` spaghetti.

---

#  3. Event Bus & Cluster

*  Event Bus API is still there, but clustering now expects you to configure explicitly (e.g., Infinispan, Hazelcast).
* ️ Some clustering SPI changes → check if you had custom cluster managers.

---

#  4. Verticles

*  Still deploy the same way, but deployment returns `Future<String>` instead of requiring a callback:

  ```java
  vertx.deployVerticle(new MyVerticle())
       .onSuccess(id -> ...)
       .onFailure(Throwable::printStackTrace);
  ```

---

#  5. Reactive APIs

*  Vert.x 5 core sticks with `Future<T>`.
*  If you want richer operators, use **Mutiny wrappers** (`vertx-mutiny-*`).
*  RxJava 2 support is gone; RxJava 3 still supported but not recommended going forward.

---

#  6. Web & HTTP

*  WebSocket API is the same, but startup methods now return `Future<HttpServer>`.
*  Routing API (`Router`) is unchanged, but more utilities are `Future`-based.
*  OpenAPI & GraphQL modules aligned with Vert.x 5.
---

#  7. Database Clients

*  Reactive DB clients (`vertx-pg-client`, `vertx-mysql-client`, etc.) unchanged, but now return `Future<RowSet<Row>>` instead of callback handlers.
*  Mutiny wrappers give you `Uni`/`Multi`.

---

#  8. Metrics, Tracing, Monitoring

*  Dropwizard and Micrometer metrics are still available, but check compatibility versions.
* ⚠️ If you used **old Dropwizard module names**, update to the new Vert.x 5 artifacts.

---

# 9. Logging

*  SLF4J remains the default logging facade.
* ️ If you had hard Netty logging bindings in 4.x, remove them — Vert.x 5 aligns Netty’s logger with SLF4J automatically.

---

#  10. Breaking Changes to Watch

* No **RxJava 2** anymore.
* All async APIs now **return `Future<T>`** — callbacks still exist in some places for compatibility, but don’t use them.
* Event bus codec registration signatures changed slightly (`MessageCodec` improvements).
* Some SPI packages (cluster manager, metrics) were refactored → check custom extensions.

---

#  Migration Strategy

1. **Update dependencies** → import the 5.0.4 BOM.
2. **Search your codebase for `Handler<AsyncResult<...>>`** → refactor to `Future<T>`.
3. **Replace RxJava 2** if you used it → migrate to RxJava 3 or Mutiny.
4. **Check custom integrations** (cluster manager, metrics, Netty handlers) → adjust to new SPI signatures.
5. Run your test suite — most code compiles fine, but async composition is where you’ll hit surprises.

---

 Bottom line: the **biggest migration step** is moving from **callbacks to `Future` composition**. Everything else (web, event bus, DB) is mostly the same, just cleaner.

# Vert.x 5.x Composable Future Patterns Guide

```
    ____            ______            ____
   / __ \___  ___  / ____/__  ___    / __ \
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \  / / / /
 / ____/  __/  __/ /_/ /  __/  __/ / /_/ /
/_/    \___/\___/\____/\___/\___/  \___\_\

PostgreSQL Event-Driven Queue System
```

**Author**: Mark A Ray-Smith Cityline Ltd.
**Date**: September 2025
**Version**: Vert.x 5.0.4 Migration Complete

---

## Overview

In the sibling PeeGeeQ project, the modules were upgraded to Vert.x 5.x and composable `Future<T>` patterns were adopted. While SmallRye Mutiny provides its own developer-first DSL with sentence-like APIs (`onItem().transform()`, `onFailure().recoverWithItem()`), Vert.x 5.x's native Future API offers its own composable patterns (`.compose()`, `.onSuccess()`, `.onFailure()`, `.map()`, `.recover()`) that prioritize functional composition and developer experience. Treat the examples below as imported reference material rather than a statement about the current `peegee-cache` codebase.

This guide demonstrates the modern Vert.x 5.x composable Future patterns that have been implemented throughout the PeeGeeQ project. These patterns provide significantly better readability, error handling, and maintainability compared to the traditional callback-style programming we've moved away from.

The migration represents a fundamental shift in how we handle asynchronous operations across all 9 PeeGeeQ modules, bringing the project in line with modern reactive programming best practices while maintaining full backward compatibility.

## Key Pattern: Composable Future Chains

### ✅ Modern Vert.x 5.x Style (RECOMMENDED)

```java
server.listen(8080)
  .compose(s -> doWarmupQuery())     // returns Future<Void>
  .compose(v -> registerWithRegistry()) // returns Future<Void>
  .onSuccess(v -> System.out.println("Server is ready"))
  .onFailure(Throwable::printStackTrace);
```

### ❌ Old Callback Style (AVOID)

```java
server.listen(8080, ar -> {
    if (ar.succeeded()) {
        doWarmupQuery(warmupResult -> {
            if (warmupResult.succeeded()) {
                registerWithRegistry(registryResult -> {
                    if (registryResult.succeeded()) {
                        System.out.println("Server is ready");
                    } else {
                        registryResult.cause().printStackTrace();
                    }
                });
            } else {
                warmupResult.cause().printStackTrace();
            }
        });
    } else {
        ar.cause().printStackTrace();
    }
});
```

## Implemented Patterns in PeeGeeQ

### 1. Server Startup with Sequential Operations

**File**: `peegeeq-service-manager/src/main/java/dev/mars/peegeeq/servicemanager/PeeGeeQServiceManager.java`

```java
// Modern composable startup
vertx.createHttpServer()
    .requestHandler(router)
    .listen(port)
    .compose(httpServer -> {
        server = httpServer;
        logger.info("PeeGeeQ Service Manager started successfully on port {}", port);
        
        // Register this service manager with Consul (optional)
        return registerSelfWithConsul()
            .recover(throwable -> {
                logger.warn("Failed to register with Consul (continuing without Consul): {}", 
                        throwable.getMessage());
                // Continue even if Consul registration fails
                return Future.succeededFuture();
            });
    })
    .compose(v -> {
        logger.info("Service Manager registered with Consul");
        return Future.succeededFuture();
    })
    .onSuccess(v -> startPromise.complete())
    .onFailure(cause -> {
        logger.error("Failed to start PeeGeeQ Service Manager", cause);
        startPromise.fail(cause);
    });
```

### 2. Database Operations with Error Recovery

**File**: `peegeeq-examples/src/main/java/dev/mars/peegeeq/examples/ModernVertxCompositionExample.java`

```java
return client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
    .sendJsonObject(setupRequest)
    .compose(response -> {
        if (response.statusCode() == 200) {
            JsonObject result = response.bodyAsJsonObject();
            logger.info("✅ Database setup created: {}", result.getString("message"));
            return Future.<Void>succeededFuture();
        } else {
            return Future.<Void>failedFuture("Database setup failed with status: " + response.statusCode());
        }
    })
    .recover(throwable -> {
        logger.warn("⚠️ Database setup failed, using fallback configuration: {}", throwable.getMessage());
        return performFallbackDatabaseSetup(client);
    });
```

### 3. Service Interactions with Health Checks

```java
return client.get(REST_PORT, "localhost", "/health")
    .send()
    .compose(healthResponse -> {
        logger.info("✅ REST API health check: {}", healthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/health").send();
    })
    .compose(serviceHealthResponse -> {
        logger.info("✅ Service Manager health check: {}", serviceHealthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances").send();
    })
    .compose(instancesResponse -> {
        if (instancesResponse.statusCode() == 200) {
            logger.info("✅ Retrieved service instances: {}", instancesResponse.bodyAsJsonArray().size());
        }
        return Future.<Void>succeededFuture();
    })
    .recover(throwable -> {
        logger.warn("⚠️ Some service interactions failed: {}", throwable.getMessage());
        return Future.<Void>succeededFuture(); // Continue despite failures
    });
```

### 4. Test Patterns - Modern vs Old Style

**✅ Modern Style** (Implemented in test files):
```java
queue.send(message)
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> fail("Failed to send message: " + throwable.getMessage()));
```

**❌ Old Style** (Refactored away):
```java
queue.send(message)
    .onComplete(ar -> {
        if (ar.succeeded()) {
            latch.countDown();
        } else {
            fail("Failed to send message: " + ar.cause().getMessage());
        }
    });
```

### 5. Resource Cleanup with Composition

**✅ Modern Style**:
```java
queue.close()
    .compose(v -> vertx.close())
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> latch.countDown()); // Continue even if close fails
```

**❌ Old Style**:
```java
queue.close()
    .onComplete(ar -> {
        vertx.close()
            .onComplete(v -> latch.countDown());
    });
```

## Key Benefits of Composable Patterns

### 1. **Better Readability**
- Linear flow instead of nested callbacks
- Clear separation of success and error paths
- Self-documenting sequential operations

### 2. **Improved Error Handling**
- Centralized error handling with `.onFailure()`
- Graceful degradation with `.recover()`
- Error propagation through the chain

### 3. **Enhanced Maintainability**
- Easier to add new steps in the sequence
- Simpler to modify individual operations
- Reduced callback hell and indentation

### 4. **Better Testing**
- Each step can be tested independently
- Clearer test failure points
- Easier to mock individual operations

## Migration Checklist

### ✅ Completed Refactoring

- [x] **Server startup patterns** - Service Manager, REST Server
- [x] **Main method deployments** - All main classes
- [x] **Database setup operations** - REST API examples
- [x] **Test patterns** - Native queue tests, outbox tests
- [x] **Resource cleanup** - Test tearDown methods
- [x] **Service interactions** - Health checks, registration

### 🔍 Areas to Monitor

- **CompletableFuture bridges** - Some legacy APIs still use CompletableFuture
- **Handler<AsyncResult<T>>** patterns - Watch for any remaining callback patterns
- **Nested .onSuccess() calls** - Should be converted to .compose() chains

## Best Practices

### 1. **Use .compose() for Sequential Operations**
```java
// ✅ Good
operation1()
    .compose(result1 -> operation2(result1))
    .compose(result2 -> operation3(result2))
    .onSuccess(finalResult -> handleSuccess(finalResult))
    .onFailure(throwable -> handleError(throwable));
```

### 2. **Use .recover() for Graceful Degradation**
```java
// ✅ Good - Continue with fallback if primary operation fails
primaryOperation()
    .recover(throwable -> {
        logger.warn("Primary failed, using fallback: {}", throwable.getMessage());
        return fallbackOperation();
    })
    .onSuccess(result -> handleResult(result));
```

### 3. **Explicit Type Parameters When Needed**
```java
// ✅ Good - Explicit type when compiler can't infer
return Future.<Void>succeededFuture();
return Future.<Void>failedFuture("Error message");
```

### 4. **Proper Resource Management**
```java
// ✅ Good - Compose cleanup operations
resource1.close()
    .compose(v -> resource2.close())
    .compose(v -> resource3.close())
    .onSuccess(v -> logger.info("All resources closed"))
    .onFailure(throwable -> logger.error("Cleanup failed", throwable));
```

## Conclusion

The PeeGeeQ project now consistently uses modern Vert.x 5.x composable Future patterns throughout the codebase. This provides better readability, maintainability, and error handling compared to callback-style programming. The patterns demonstrated here serve as a reference for future development and can be applied to any Vert.x 5.x application.

Here are the key bullets from the PGQ coding principles:

## **Core Principles Summary**

1. **Investigate First**: Understand the problem before implementing solutions
2. **Follow Patterns**: Learn from existing code in the same project
3. **Verify Assumptions**: Don't assume tests are working - check the logs
4. **Fix Root Causes**: Address configuration issues, don't mask them
5. **Document Honestly**: Write comments that reflect actual behavior
6. **Validate Incrementally**: Test each change before moving to the next
7. **Classify Tests Clearly**: Know whether you're writing unit or integration tests
8. **Fail Honestly**: Let tests fail when there are real problems to fix
9. **Read Logs Carefully**: Error messages usually tell you exactly what's wrong
10. **Use Modern Vert.x 5.x Patterns**: Always use composable Futures (`.compose()`, `.onSuccess()`, `.onFailure()`) instead of callback-style programming

## **Critical Additional Principles**

- **Work incrementally and test after each small incremental change**
- **When testing make sure you scan the test logs properly for test errors, do not rely on the exit code**
- **Use Maven debug mode (`-X`) when investigating test issues**
- **Always verify that test methods are actually executing** - Maven can report success even when test methods never run
- **Remember dependent peegeeq modules need to be installed to the local Maven repository first**
- **Do not guess. Use the coding principles. Test after every change. Read the test log output in detail after every test run.**
- **Do not continue with the next step until the tests are passing**
- **NEVER skip failing tests - it is entirely non-professional and unacceptable** - All test failures must be fixed, never skipped with `@Disabled`, `-DskipTests`, or Maven exclusions

These principles emphasize investigation before implementation, following established patterns, incremental validation, and careful log analysis - exactly what we need for the TestContainers standardization work.



remember there are dozens of examples already of how to set up test containers.

remember there are dozens of examples already of how to use vert.x 5.x. 

Do not guess. 

Use the coding principles. 

Test after every change. 

Read the test log output in detail after every test run.

Do not continue with the next step until the tests are passing.

rememeber the dependent peegeeq modules need to be installed to the local Maven repository first. 

we are coding on a windows 11 machine

---

## **Multi-Tenant Schema Isolation: Implementation Guidance**

### **Principle: "Tenant Separation is Absolute"**
**PeeGeeQ is moving to a "Single Isolated Schema per tenant" model. Shared infrastructure tables are a design smell.**

- ✅ **MANDATORY**: All tables and triggers for a given tenant must reside within that tenant's configured schema.
- ❌ **FORBIDDEN**: Hardcoding global schema names like `peegeeq` or `bitemporal` in SQL or Java code.
- ❌ **FORBIDDEN**: Assuming that global infrastructure schemas are available or reachable via `search_path`.

### **Principle: "Parametrize, Don't Hardcode"**
**All SQL templates and queries must be fully parameterizable.**

- ✅ **MANDATORY**: All SQL templates must use the `{schema}` placeholder for both table qualification and schema creation.
- ✅ **MANDATORY**: Java service methods must construct fully qualified table names using the tenant's configuration.
- ❌ **FORBIDDEN**: Unqualified table names in utility queries (e.g., `SELECT * FROM outbox`). Use `{schema}.outbox` or ensure `search_path` is explicitly set per session.

### **Principle: "Silent Neighbors (Avoid Notification Noise)"**
**Notifications must be schema-qualified to prevent cross-tenant "thundering herd" issues.**

- ✅ **MANDATORY**: `LISTEN` and `NOTIFY` channel names must be prefixed with the tenant schema (e.g., `{schema}_{topic}_changes`).
- ✅ **MANDATORY**: `ReactiveNotificationHandler` must be schema-aware and construct channel names dynamically.
- ❌ **FORBIDDEN**: Global notification channels (e.g., `bitemporal_events`) that broadcast to all tenants in a shared database.

### **Principle: "Standardized Configuration"**
**Only one configuration key should rule the schema resolution.**

- ✅ **MANDATORY**: Use `peegeeq.database.schema` as the single source of truth.
- ❌ **FORBIDDEN**: Implementing module-specific defaults (like the `peegeeq` vs `public` discrepancy found in REST handler).
- ✅ **MANDATORY**: The `PeeGeeQRuntime` must validate that the schema is consistently applied across all enabled features (Native, Outbox, Bi-Temporal).

### **Verification of Isolation**
**Every PR touching schema logic must include a multi-tenant isolation test.**
- Tests must use `TestContainers` to spin up a real PostgreSQL instance.
- Tests must create two distinct schemas (e.g., `tenant_a`, `tenant_b`).
- Validation must prove that `tenant_a` cannot `LISTEN` to `tenant_b`'s events or `SELECT` from its tables.


---

## Source: docs/guidelines/Vertx-5x-Patterns-Guide.md


# Vert.x 5.x Patterns Guide

Imported reference note:

- this guide was copied from the sibling `peegeeq` repository
- module paths and implementation examples below refer to that repository unless stated otherwise
- in `peegee-cache`, use this document as style and architecture guidance, not as proof that the referenced files exist here

```
    ____            ______            ____
   / __ \___  ___  / ____/__  ___    / __ \
  / /_/ / _ \/ _ \/ / __/ _ \/ _ \  / / / /
 / ____/  __/  __/ /_/ /  __/  __/ / /_/ /
/_/    \___/\___/\____/\___/\___/  \___\_\

PostgreSQL Event-Driven Queue System
```

**Author**: Mark A Ray-Smith Cityline Ltd.
**Date**: September 2025
**Version**: Vert.x 5.0.4 Complete Guide

---

## Overview

This comprehensive guide covers two essential aspects of Vert.x 5.x development in PeeGeeQ:

1. **Composable Future Patterns** - Modern asynchronous programming patterns using Vert.x 5.x Future API
2. **Performance Optimization** - Research-based performance tuning for PostgreSQL operations

The sibling PeeGeeQ project was upgraded to Vert.x 5.x, implementing modern composable Future patterns across its modules while pursuing performance optimization for PostgreSQL workloads.

Note: Vert.x 5 uses Future-only APIs and builder patterns across the stack. For context and migration guidance, see: https://vertx.io/docs/guides/vertx-5-migration-guide/


---

# Section 1: Composable Future Patterns

## Modern Vert.x 5.x Composable Patterns

Vert.x 5.x provides elegant composable Future patterns (`.compose()`, `.onSuccess()`, `.onFailure()`, `.map()`, `.recover()`) that prioritize functional composition and developer experience. This section demonstrates the patterns implemented throughout PeeGeeQ, transforming the codebase from callback-style programming to modern, composable asynchronous patterns.

### Key Pattern: Composable Future Chains

#### ✅ Modern Vert.x 5.x Style (RECOMMENDED)

```java
server.listen(8080)
  .compose(s -> doWarmupQuery())     // returns Future<Void>
  .compose(v -> registerWithRegistry()) // returns Future<Void>
  .onSuccess(v -> System.out.println("Server is ready"))
  .onFailure(Throwable::printStackTrace);
```

#### ❌ Old Callback Style (AVOID)

```java
server.listen(8080, ar -> {
    if (ar.succeeded()) {
        doWarmupQuery(warmupResult -> {
            if (warmupResult.succeeded()) {
                registerWithRegistry(registryResult -> {
                    if (registryResult.succeeded()) {
                        System.out.println("Server is ready");
                    } else {
                        registryResult.cause().printStackTrace();
                    }
                });
            } else {
                warmupResult.cause().printStackTrace();
            }
        });
    } else {
        ar.cause().printStackTrace();
    }
});
```

## Implemented Patterns in PeeGeeQ

### 1. Server Startup with Sequential Operations

**File**: `peegeeq-service-manager/src/main/java/dev/mars/peegeeq/servicemanager/PeeGeeQServiceManager.java`

```java
// Modern composable startup
vertx.createHttpServer()
    .requestHandler(router)
    .listen(port)
    .compose(httpServer -> {
        server = httpServer;
        logger.info("PeeGeeQ Service Manager started successfully on port {}", port);

        // Register this service manager with Consul (optional)
        return registerSelfWithConsul()
            .recover(throwable -> {
                logger.warn("Failed to register with Consul (continuing without Consul): {}",
                        throwable.getMessage());
                // Continue even if Consul registration fails
                return Future.succeededFuture();
            });
    })
    .compose(v -> {
        logger.info("Service Manager registered with Consul");
        return Future.succeededFuture();
    })
    .onSuccess(v -> startPromise.complete())
    .onFailure(cause -> {
        logger.error("Failed to start PeeGeeQ Service Manager", cause);
        startPromise.fail(cause);
    });
```

### 2. Database Operations with Error Recovery

```java
return client.post(REST_PORT, "localhost", "/api/v1/database-setup/create")
    .sendJsonObject(setupRequest)
    .compose(response -> {
        if (response.statusCode() == 200) {
            JsonObject result = response.bodyAsJsonObject();
            logger.info("✅ Database setup created: {}", result.getString("message"));
            return Future.succeededFuture();
        } else {
            return Future.<Void>failedFuture("Database setup failed with status: " + response.statusCode());
        }
    })
    .recover(throwable -> {
        logger.warn("⚠️ Database setup failed, using fallback configuration: {}", throwable.getMessage());
        return performFallbackDatabaseSetup(client);
    });
```

### 3. Service Interactions with Health Checks

```java
return client.get(REST_PORT, "localhost", "/health")
    .send()
    .compose(healthResponse -> {
        logger.info("✅ REST API health check: {}", healthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/health").send();
    })
    .compose(serviceHealthResponse -> {
        logger.info("✅ Service Manager health check: {}", serviceHealthResponse.statusCode());
        return client.get(SERVICE_MANAGER_PORT, "localhost", "/api/v1/instances").send();
    })
    .compose(instancesResponse -> {
        if (instancesResponse.statusCode() == 200) {
            logger.info("✅ Retrieved service instances: {}", instancesResponse.bodyAsJsonArray().size());
        }
        return Future.succeededFuture();
    })
    .recover(throwable -> {
        logger.warn("⚠️ Some service interactions failed: {}", throwable.getMessage());
        return Future.succeededFuture(); // Continue despite failures
    });
```

### 4. Test Patterns - Modern vs Old Style

**✅ Modern Style** (Implemented in test files):
```java
queue.send(message)
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> fail("Failed to send message: " + throwable.getMessage()));
```

**❌ Old Style** (Refactored away):
```java
queue.send(message)
    .onComplete(ar -> {
        if (ar.succeeded()) {
            latch.countDown();
        } else {
            fail("Failed to send message: " + ar.cause().getMessage());
        }
    });
```

### 5. Resource Cleanup with Composition

**✅ Modern Style**:
```java
queue.close()
    .compose(v -> vertx.close())
    .onSuccess(v -> latch.countDown())
    .onFailure(throwable -> latch.countDown()); // Continue even if close fails
```

## Key Benefits of Composable Patterns

### 1. **Better Readability**
- Linear flow instead of nested callbacks
- Clear separation of success and error paths
- Self-documenting sequential operations

### 2. **Improved Error Handling**
- Centralized error handling with `.onFailure()`
- Graceful degradation with `.recover()`
- Error propagation through the chain

### 3. **Enhanced Maintainability**
- Easier to add new steps in the sequence
- Simpler to modify individual operations
- Reduced callback hell and indentation

### 4. **Better Testing**
- Each step can be tested independently
- Clearer test failure points
- Easier to mock individual operations

## Best Practices for Composable Patterns

### 1. **Use .compose() for Sequential Operations**
```java
// ✅ Good
operation1()
    .compose(result1 -> operation2(result1))
    .compose(result2 -> operation3(result2))
    .onSuccess(finalResult -> handleSuccess(finalResult))
    .onFailure(throwable -> handleError(throwable));
```

### 2. **Use .recover() for Graceful Degradation**
```java
// ✅ Good - Continue with fallback if primary operation fails
primaryOperation()
    .recover(throwable -> {
        logger.warn("Primary failed, using fallback: {}", throwable.getMessage());
        return fallbackOperation();
    })
    .onSuccess(result -> handleResult(result));
```

### 3. Return Void cleanly
```java
// ✅ Good - return Void without boilerplate
return Future.succeededFuture();        // immediate success
return someFuture.mapEmpty();           // end of chain producing Void
return Future.failedFuture("Error");   // failure case
```

### 4. **Proper Resource Management**
```java
// ✅ Good - Compose cleanup operations
resource1.close()
    .compose(v -> resource2.close())
    .compose(v -> resource3.close())
    .onSuccess(v -> logger.info("All resources closed"))
    .onFailure(throwable -> logger.error("Cleanup failed", throwable));
```


### Common Anti-Patterns to Avoid

#### 1. Never Create Vert.x Instances in Factories

❌ Wrong:
```java
// OutboxFactory creating its own Vert.x - NEVER DO THIS
new PgClientFactory(Vertx.vertx())
```

✅ Correct:
```java
// Use shared Vert.x instance from application context
public OutboxFactory(Vertx vertx, DatabaseService databaseService, ...) {
    this.vertx = Objects.requireNonNull(vertx);
}
```

Why: Creating Vert.x spawns new event-loop threads with independent lifecycle, causing thread leaks, double metrics, and unreliable shutdown.

#### 2. Don't Mix ScheduledThreadPoolExecutor with Vert.x

❌ Wrong:
```java
scheduledExecutor.scheduleAtFixedRate(task, 0, 30, SECONDS);
```

✅ Correct:
```java
// Use Vert.x timers with executeBlocking for potentially blocking work
vertx.setPeriodic(TimeUnit.SECONDS.toMillis(30), id -> {
    vertx.executeBlocking(() -> {
        // Blocking work here
        return null;
    });
});
```

#### 3. Avoid Blocking Operations on Event Loop

❌ Wrong:
```java
public void start() {
    startReactive().toCompletionStage().toCompletableFuture().get(30, SECONDS);
}
```

✅ Correct:
```java
public void start() {
    if (Vertx.currentContext() != null && Vertx.currentContext().isEventLoopContext()) {
        throw new IllegalStateException("Do not call blocking start() on event-loop thread");
    }
    // proceed with blocking call on worker thread
}


```

#### 4. Don't Use JDBC Patterns in Reactive Code

❌ Wrong:
```java
// JDBC-style configuration that Vert.x ignores
private final long connectionTimeout; // ambiguous units
private final int minimumIdle;        // not used by Vert.x
private final boolean autoCommit;     // JDBC-only concept
```


#### 3b. Virtual threads for integrating blocking libraries

Vert.x 5 allows handlers to run on virtual threads. When you must call a blocking library, prefer virtual threads or `executeBlocking` so event loops remain unblocked. Keep database I/O via the reactive SQL client non-blocking.

```java
// Example: run a blocking handler on virtual threads (Vert.x 5)
router.get("/legacy").handler(ThreadingModel.VIRTUAL_THREAD, ctx -> {
  legacyClient.blockingCall(); // blocking call here; does not block event loops
  ctx.end("ok");
});
```

✅ Correct:
```java
// Vert.x-native configuration
private final Duration connectionTimeout;
private final int maxWaitQueueSize; // Vert.x pool concept
private final SslMode sslMode;      // Vert.x SSL configuration
```


### Additional Error Handling Patterns

#### Fail Fast Validation
```java
private static void validate(String clientId, PgConnectionConfig conn, PgPoolConfig pool) {
    if (clientId == null || clientId.isBlank())
        throw new IllegalArgumentException("clientId must be non-blank");
    Objects.requireNonNull(conn, "connectionConfig");
    Objects.requireNonNull(pool, "poolConfig");
}
```

#### Graceful Degradation in Periodic Tasks
```java
vertx.setPeriodic(intervalMs, id -> {
    vertx.executeBlocking(() -> {
        try {
            performMaintenanceTask();
            return null;
        } catch (Exception e) {
            logger.warn("Maintenance task failed", e);
            return null; // Don't fail the periodic timer
        }
    });
});
```

### Recommended Factory Pattern with Dependency Injection
```java
// Proper factory that doesn't create Vert.x instances
public class OutboxFactory implements MessageFactory {
    private final Vertx vertx; // Injected, not created
    private final DatabaseService databaseService;

    public OutboxFactory(Vertx vertx, DatabaseService databaseService, ...) {
        this.vertx = Objects.requireNonNull(vertx);
        this.databaseService = Objects.requireNonNull(databaseService);
    }

    @Override
    public <T> MessageProducer<T> createProducer(String topic, Class<T> payloadType) {
        validateTopic(topic, payloadType);
        return new OutboxProducer<>(vertx, topic, payloadType, clientFactory, objectMapper, metrics);
    }
}
```
#### CompletableFuture Design in PeeGeeQ APIs

All PeeGeeQ producer methods return `CompletableFuture<Void>` rather than Vert.x `Future<Void>`. This is intentional:

**Why CompletableFuture?**
- **Interoperability**: Works seamlessly in both Spring Boot and Vert.x applications
- **Familiar API**: Standard Java API that most developers know
- **Simplicity**: Single consistent API instead of separate `Future`-returning overloads
- **No Overhead**: Works directly in Vert.x handlers without conversion

**When Conversion is Needed:**
Conversion is only required when composing with other Vert.x `Future` operations:

```java
// Rare case: composing with Vert.x Future operations
pool.withConnection(connection -> {
    return connection.preparedQuery(sql).execute(params)
        .compose(result -> {
            // Convert only when composing with other Futures
            return producer.sendInTransaction(event, connection)
                .toCompletionStage().toCompletableFuture()
                .handle((v, error) -> {
                    if (error != null) return Future.failedFuture(error);
                    return Future.succeededFuture();
                });
        });
});
```

**Common Cases (No Conversion Needed):**
```java
// Spring Boot: use directly
producer.send(event).thenAccept(v -> logger.info("Sent"));

// Vert.x event bus: use directly
producer.send(event).thenAccept(v ->
    vertx.eventBus().send("order.created", event)
);
```

This design avoids API explosion (48 methods instead of 24) while maintaining full compatibility with both frameworks.


### Refactoring standards distilled from the “-review” documents

These cross-cutting patterns recur throughout the migration reviews. They complement and tighten the guidance above.

#### Factory and DI standards
- Inject Vertx; never call Vertx.vertx() inside factories/components. Reuse the injected instance (or Vertx.currentContext().owner() only if you are already inside Vert.x), and let a top-level owner manage its lifecycle.
- Centralize lifecycle at the boundary (service manager/app). Lower layers must not close shared Vertx.
- Prefer Vert.x timers + executeBlocking for periodic/possibly blocking work; keep DB I/O reactive via the SQL client.
- Do not read “fallback” system properties inside libraries to build DB configs. Accept explicit, validated configuration from the caller and fail fast if required pieces are missing.
- Avoid reflection to reach into DatabaseService internals; evolve typed APIs instead (e.g., expose vertx(), connectionManager(), pool(name), metrics()).

#### PgClientFactory / ConnectionManager patterns
- Hold a single shared Pool per service/tenant (keyed by ID). Create idempotently with computeIfAbsent and roll back the map entry if creation fails.
- Provide removal APIs (removePoolAsync / removeClientAsync) and make close non-blocking by returning Future<Void>. Aggregate multiple closes with Future.all(...). See Shutdown Coordination Patterns below.
- Align configuration with Vert.x concepts: PoolOptions.maxSize, maxWaitQueueSize, idleTimeout, connectionTimeout, shared; use Duration in your own config types and map to Vert.x setters.
- Validate inputs early (host, port range, database, SSL materials). Log sanitized config (never passwords or secrets) and fail fast.

Example idempotent creation + removal
```java
private final ConcurrentHashMap<String, Pool> pools = new ConcurrentHashMap<>();

public Pool getOrCreateReactivePool(String id, PgConnectionConfig cfg, PgPoolConfig poolCfg) {
  return pools.computeIfAbsent(id, key -> {
    try {
      Pool p = createReactivePool(cfg, poolCfg);
      return p;
    } catch (Throwable t) {
      pools.remove(key);
      throw t;
    }
  });
}

public Future<Void> removePoolAsync(String id) {
  Pool p = pools.remove(id);
  if (p == null) return Future.succeededFuture();
  return p.close().mapEmpty();
}
```

#### Lifecycle API surface
- Expose startReactive()/stopReactive()/closeReactive() that return Future<Void>. Keep AutoCloseable.close() only as a best-effort shim and avoid blocking on event loops. If you must keep a blocking close(), guard with an event-loop check and delegate to the reactive version.
- Pool.close() is asynchronous in Vert.x 5; await it (or aggregate with Future.all) before tearing down dependent components.

#### Health checks
- Implement real checks via pool.withConnection(conn -> conn.query("SELECT 1").execute().map(true)). Do not return synthetic “OK”. See “Operational Health Checks” section for a complete example.

---


---

# Section 2: Performance Optimization

## Vert.x 5.x PostgreSQL Performance Optimization

This section implements the official Vert.x 5.x PostgreSQL performance checklist and advanced optimization techniques to maximize PeeGeeQ throughput and minimize latency. Based on extensive research of Vert.x 5.x documentation, GitHub examples, and real-world performance testing.

## 🎯 Performance Results Achieved

Through careful implementation of Vert.x 5.x best practices, PeeGeeQ achieved significant performance improvements:

| Implementation | Before Optimization | After Vert.x 5.x | Improvement |
|----------------|-------------------|------------------|-------------|
| **Pool Size** | 32 | 100 | +213% |
| **Wait Queue** | 200 | 1000 | +400% |
| **Bitemporal Throughput** | 155 msg/sec | 904 msg/sec | +483% |
| **Test Success Rate** | 40% | 60% | +50% |

## 🚀 Critical Vert.x 5.x Architecture Insights

### Pipelining: how it actually works in Vert.x 5

- Fact: Command pipelining is a connection-level feature. Enable it with `PgConnectOptions#setPipeliningLimit(...)`. The default is 256; setting it to 1 disables pipelining.
- Pool-created connections and pooled `SqlClient` connections honor this setting. Typically you set it when you build a `Pool`/`SqlClient` so the pool creates connections with that option.
- Nuance: pool operations borrow a connection per operation; to pipeline multiple commands on the same connection, keep the connection (e.g., `withConnection`/`withTransaction`) or use a facade that supports pool-level pipelining. Measure — gains depend on workload and network RTT.

```java
PgConnectOptions connectOptions = new PgConnectOptions()
  .setHost(host)
  .setPort(port)
  .setDatabase(db)
  .setUser(user)
  .setPassword(pass)
  .setPipeliningLimit(16); // enable connection-level pipelining

Pool pool = PgBuilder.pool()
  .connectingTo(connectOptions)
  .with(poolOptions)
  .using(vertx)
  .build();

// To pipeline multiple commands on the same connection, hold it:
pool.withConnection(conn ->
  conn.preparedQuery("SELECT 1").execute()
      .compose(rs -> conn.preparedQuery("SELECT 2").execute())
);
```

## Performance Checklist Implementation

### 1. ✅ Set pool size (not 4): try 16/32 and tune with your DBA

**Research Finding**: Vert.x documentation recommends 16-32 for most workloads, but high-concurrency scenarios require larger pools.

**Default Configuration:**
```properties
# peegeeq-default.properties
peegeeq.database.pool.min-size=8
peegeeq.database.pool.max-size=32
```

**High-Concurrency Configuration (Bitemporal Workloads):**
```properties
# peegeeq-bitemporal-optimized.properties
peegeeq.database.pool.max-size=100  # Increased for complex temporal queries
peegeeq.database.pool.min-size=20
```

**Production-Tested Configuration:**
```properties
# Based on real performance testing results
peegeeq.database.pool.max-size=100
peegeeq.database.pool.wait-queue-multiplier=10  # 1000 wait queue size
```

**Code Implementation:**
```java
// PgBiTemporalEventStore.java - Research-based optimization
private int getConfiguredPoolSize() {
    // Check system property first (allows runtime tuning)
    String systemPoolSize = System.getProperty("peegeeq.database.pool.max-size");
    if (systemPoolSize != null) {
        return Integer.parseInt(systemPoolSize);
    }

    // Start conservative and measure (avoid hard-coding large defaults)
    // Use system property to override when needed
    int defaultSize = 32; // Start at 16–32; tune based on p95 latency and pool wait
    logger.info("Using initial pool size: {} (adjust via -Dpeegeeq.database.pool.max-size)", defaultSize);
    return defaultSize;
}
```

### 2. ✅ Share one pool across all verticles (setShared(true))

**Research Finding**: Shared pools are essential for Vert.x 5.x performance. Each pool creates its own connection management overhead. Use `PoolOptions#setShared(true)` and `setName("...")` to share/name pools.

**Configuration:**
```properties
peegeeq.database.pool.shared=true
peegeeq.database.pool.name=peegeeq-shared-pool  # Named pools for monitoring
```

**Advanced Implementation:**
```java
// PgBiTemporalEventStore.java - Production-grade shared pool configuration
PoolOptions poolOptions = new PoolOptions();
poolOptions.setMaxSize(maxPoolSize);

// CRITICAL PERFORMANCE FIX: Share one pool across all verticles (Vert.x 5.x best practice)
poolOptions.setShared(true);
poolOptions.setName("peegeeq-bitemporal-pool"); // Named shared pool for monitoring

// CRITICAL FIX: Set wait queue size to 10x pool size to handle high-concurrency scenarios
// Based on performance test failures, bitemporal workloads need larger wait queues
poolOptions.setMaxWaitQueueSize(maxPoolSize * 10);

// Connection timeout and idle timeout for reliability
poolOptions.setConnectionTimeout(30000); // 30 seconds
poolOptions.setIdleTimeout(600000); // 10 minutes
```

**Wait Queue Size Note:**
In Vert.x 5, the max wait queue size is unbounded by default (`-1`). If you set it to a finite value (e.g., 200), you'll see "max wait queue size reached" when you hit back-pressure — that's expected. For bursty workloads, consider sizing it relative to pool size (e.g., 5–10x) and measure the impact.

### 3. ✅ Deploy multiple instances of your verticles (≃ cores)

**Configuration:**
```properties
peegeeq.verticle.instances=8
```

**Code Implementation:**
```java
// VertxPerformanceOptimizer.java
public static DeploymentOptions createOptimizedDeploymentOptions() {
    int instances = getOptimalVerticleInstances(); // ≃ cores
    return new DeploymentOptions().setInstances(instances);
}
```

**Usage:**
```java
DeploymentOptions options = VertxPerformanceOptimizer.createOptimizedDeploymentOptions();
vertx.deployVerticle(() -> new PeeGeeQRestServer(8080), options);
```

### 4. ✅ Don't hold a SqlConnection for the whole app; use pool ops or short-lived withConnection

**Best Practice Implementation:**
```java
// Use pool.withConnection() for short-lived operations
pool.withConnection(connection -> {
    return connection.preparedQuery("INSERT INTO events ...")
        .execute(tuple);
}).onSuccess(result -> {
    // Connection automatically returned to pool
});

// Use pool.withTransaction() for transactional operations
pool.withTransaction(connection -> {
    return connection.preparedQuery("INSERT ...")
        .execute(tuple)
        .compose(r -> connection.preparedQuery("UPDATE ...")
            .execute(tuple2));
});
```

### 5. ✅ Keep transactions short, and don't wrap everything in a tx

**Implementation Pattern:**
```java
// Good: Short, focused transactions
public Future<Void> appendEvent(String streamId, String eventData) {
    return pool.withTransaction(connection -> {
        // Single, focused operation
        return connection.preparedQuery(INSERT_EVENT_SQL)
            .execute(Tuple.of(streamId, eventData));
    }).mapEmpty();
}

// Avoid: Long transactions that hold locks
// Don't wrap multiple unrelated operations in one transaction


```


### Transaction Patterns
```java
// Transaction with automatic retries for serialization/deadlock failures
public <T> Future<T> inTransaction(Function<SqlConnection, Future<T>> work) {
    return pool.withTransaction(TransactionOptions.options().setDeferrable(true), conn ->
        work.apply(conn)
    ).recover(err -> {
        if (isRetryable(err)) {
            return retry(work);
        }
        return Future.failedFuture(err);
    });
}

private boolean isRetryable(Throwable err) {
    String code = pgErrorCode(err);
    return "40001".equals(code) || "40P01".equals(code);
}



```

Note on transaction propagation: When composing layered services, prefer `TransactionPropagation.CONTEXT` so nested service calls reuse the current transaction/connection where appropriate. See Vert.x 5 SQL client javadoc for `io.vertx.sqlclient.TransactionPropagation`.


### 6. Enable pipelining on connections; benchmark gains

- Pipelining is configured on `PgConnectOptions` via `setPipeliningLimit` (default 256). Both `Pool` and pooled `SqlClient` honor it.
- To pipeline multiple commands on a single connection, keep the connection using `withConnection`/`withTransaction`. Pool-level pipelining depends on workload and client behavior; measure effects.
- Caution: larger pipelines may be problematic with some L4/L7 proxies. Start with the default (256) and increase only if measurements justify.

**Configuration:**
```properties
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=256  # Default; tune based on measurements
```

**Implementation:**
```java
int pipeliningLimit = Integer.parseInt(
    System.getProperty("peegeeq.database.pipelining.limit", "256"));
connectOptions.setPipeliningLimit(pipeliningLimit);
logger.info("Configured PostgreSQL pipelining limit: {}", pipeliningLimit);

// Build Pool and/or SqlClient; both honor connection-level pipelining
reactivePool = PgBuilder.pool()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx) // Injected Vertx instance
    .build();

pipelinedClient = PgBuilder.client()
    .with(poolOptions)
    .connectingTo(connectOptions)
    .using(vertx) // Injected Vertx instance
    .build();
```

**Read-path selection:**
```java
private SqlClient getOptimalReadClient() {
    if (pipelinedClient != null) {
        return pipelinedClient;
    }
    return reactivePool;
}
```

### 7. ✅ Measure: p95 latency, pool wait time, DB CPU and iowait

**Performance Monitoring:**
```java
// SimplePerformanceMonitor.java - Essential metrics
public class SimplePerformanceMonitor {


    public void recordQueryTime(Duration duration);
    public void recordConnectionTime(Duration duration);
    public double getAverageQueryTime();
    public double getAverageConnectionTime();
    public long getMaxQueryTime();
    public long getMaxConnectionTime();
}
```

**Usage:**
```java
SimplePerformanceMonitor monitor = new SimplePerformanceMonitor();
monitor.startPeriodicLogging(vertx, 10000); // Log every 10 seconds

// Manual timing
var timing = monitor.startTiming();
// ... perform operation ...
timing.recordAsQuery();
```

## 🎯 Real-World Performance Testing Results

### PeeGeeQ Implementation Comparison

| Implementation | Throughput | Architecture | Key Optimizations |
|----------------|------------|--------------|-------------------|
| **Native Queue** | 10,000+ msg/sec | LISTEN/NOTIFY + Advisory Locks | Real-time messaging, <10ms latency |
| **Outbox Pattern** | 5,000+ msg/sec | Transactional safety | JDBC vs Reactive comparison |
| **Bitemporal (Before)** | 155 msg/sec | Event sourcing | Connection pool exhaustion |
| **Bitemporal (After Vert.x 5.x)** | 904 msg/sec | Event sourcing + Optimized | **483% improvement** |

### Performance Test Results

**Before Vert.x 5.x Optimization:**
```
[ERROR] Connection pool reached max wait queue size of 200
[ERROR] Tests run: 10, Failures: 1, Errors: 5, Skipped: 0
```

**After Vert.x 5.x Optimization:**
```
[INFO] CRITICAL: Created optimized Vert.x infrastructure:
       pool(size=100, shared=true, waitQueue=1000, eventLoops=16),
       pipelinedClient(limit=256)
[INFO] Tests run: 10, Failures: 2, Errors: 4, Skipped: 0
[INFO] Bitemporal throughput: 904 events/sec (example result; benchmark your workload)
```

## Configuration Profiles

### Research-Based High-Performance Profile
```properties
# peegeeq-vertx5-optimized.properties - Based on official Vert.x research
peegeeq.database.pool.max-size=100
peegeeq.database.pool.min-size=20
peegeeq.database.pool.shared=true
peegeeq.database.pool.name=peegeeq-optimized-pool
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=256
peegeeq.database.event.loop.size=16
peegeeq.database.worker.pool.size=32
peegeeq.verticle.instances=8
```

### Production Profile (Conservative)
```properties
# peegeeq-production.properties - Conservative settings for production
peegeeq.database.pool.max-size=50
peegeeq.database.pool.min-size=10
peegeeq.database.pool.shared=true
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=256
peegeeq.database.event.loop.size=8
```

### Extreme High-Concurrency Profile
```properties
# peegeeq-extreme-performance.properties - For maximum throughput scenarios
peegeeq.database.pool.max-size=200
peegeeq.database.pool.min-size=50
peegeeq.database.pool.shared=true
peegeeq.database.pool.wait-queue-multiplier=20  # 4000 wait queue
peegeeq.database.pipelining.enabled=true
peegeeq.database.pipelining.limit=512
peegeeq.database.event.loop.size=32
peegeeq.database.worker.pool.size=64
```

## Configuration Best Practices

### Connection Configuration
```java
public final class PgConnectionConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password; // Consider char[] for security
    private final SslMode sslMode; // Vert.x native SSL
    private final Duration connectTimeout;
    private final int reconnectAttempts;

    // Remove JDBC artifacts like getJdbcUrl()
    @Deprecated(forRemoval = true)
    public String getJdbcUrl() {
        throw new UnsupportedOperationException("Not used in Vert.x reactive mode");
    }
}
```

Note on schema/search_path
- The reactive PG client does not honor JDBC-style schema fields. If you need a non-default schema, set it explicitly after you obtain a connection, e.g. `pool.withConnection(conn -> conn.query("SET search_path TO my_schema").execute().mapEmpty())`, or ensure your SQL qualifies objects.

### Pool Configuration
```java
public final class PgPoolConfig {
    private final int maxSize; // Match Vert.x API names
    private final int maxWaitQueueSize; // Critical for backpressure
    private final Duration idleTimeout;
    private final Duration connectionTimeout;
    private final boolean shared;

    // Remove JDBC-only fields
    // private final int minimumIdle; // Not used by Vert.x
    // private final Duration maxLifetime; // Not used by Vert.x
}
```

## Security Considerations

### Password Handling
```java
@Override
public String toString() {
    return "PgConnectionConfig{" +
        "host='" + host + '\'' +
        ", database='" + database + '\'' +
        ", user='" + username + '\'' +
        ", sslMode=" + sslMode +
        '}'; // No password field
}
```

### SSL Configuration
```java
PgConnectOptions connectOptions = new PgConnectOptions()
    .setSslMode(config.getSslMode())
    .setTrustAll(config.isTrustAll()) // Only for dev
    .setPemTrustOptions(new PemTrustOptions()
        .addCertPath(config.getCaCertPath()));
```


#### Timeouts: PoolOptions vs SqlConnectOptions

- `PoolOptions#setConnectionTimeout(...)`: time to wait for a connection from the pool.
- `SqlConnectOptions#setConnectTimeout(...)`: TCP/DB connect timeout (initial socket connect).
- Idle connection cleanup is controlled by idle-timeout on the pool; ensure you apply the correct units when setting via config (e.g., map `Duration` to the corresponding numeric + time unit setters in the Vert.x 5 API).

#### Prepared statement cache (hot paths)

For frequently executed queries, tune the prepared statement cache on `SqlConnectOptions`:

```java
connectOptions
  .setCachePreparedStatements(true)
  .setPreparedStatementCacheMaxSize(256);
```

As with other settings, measure hit rates and memory impact under production-like load.


## 🔧 Advanced Vert.x 5.x Optimization Techniques

### Event Loop and Worker Pool Optimization

**Research Finding**: Vert.x 5.x allows fine-tuning of event loops and worker pools for database-intensive workloads.

```java
// Application boundary (main): configure Vert.x once and inject everywhere
public final class App {
  public static void main(String[] args) {
    VertxOptions opts = new VertxOptions()
      .setEventLoopPoolSize(Integer.getInteger("peegeeq.database.event.loop.size", 16))
      .setWorkerPoolSize(Integer.getInteger("peegeeq.database.worker.pool.size", 32))
      .setPreferNativeTransport(true);

    Vertx vertx = Vertx.vertx(opts);
    MyService svc = new MyService(vertx /* inject into libraries/components */, ...);
    // ... start your app ...
  }
}

// Library/component: accept Vert.x via DI; never create Vertx.vertx() here
public final class PgBiTemporalEventStore {
  private final Vertx vertx;
  public PgBiTemporalEventStore(Vertx vertx, ...) {
    this.vertx = Objects.requireNonNull(vertx);
  }
}
```

### Batch Operations for Maximum Throughput

**Research Finding**: Vert.x documentation emphasizes: "Batch/bulk when you can (executeBatch), or use multi-row INSERT … VALUES (...), (...), ... to cut round-trips."

```java
/**
 * PERFORMANCE OPTIMIZATION: Batch append multiple bi-temporal events for maximum throughput.
 * This implements the "fast path" recommended by Vert.x research for massive concurrent writes.
 */
public CompletableFuture<List<BiTemporalEvent<T>>> appendBatch(List<BatchEventData<T>> events) {
    if (events == null || events.isEmpty()) {
        return CompletableFuture.completedFuture(List.of());
    }

    logger.debug("BITEMPORAL-BATCH: Appending {} events in batch for maximum throughput", events.size());

    // Use pipelined client for maximum batch performance
    SqlClient client = getHighPerformanceWriteClient();
    return client.preparedQuery(sql).executeBatch(batchParams)
        .map(rowSet -> {
            // Process results...
            logger.debug("BITEMPORAL-BATCH: Successfully appended {} events in batch", results.size());
            return results;
        });
}
```

### Connection Pool Resource Management

**Critical Implementation**: Proper cleanup prevents resource leaks.

```java
@Override
public void close() {
    if (closed) return;

    logger.info("Closing bi-temporal event store");
    closed = true;

    // CRITICAL: Close pipelined client to prevent resource leaks
    if (pipelinedClient != null) {
        try {
            pipelinedClient.close();
            logger.debug("Closed pipelined client");
        } catch (Exception e) {
            logger.warn("Error closing pipelined client: {}", e.getMessage(), e);
        }
    }

    // Close reactive pool
    if (reactivePool != null) {
        try {
            reactivePool.close();
            logger.debug("Closed reactive pool");
        } catch (Exception e) {
            logger.warn("Error closing reactive pool: {}", e.getMessage(), e);
        }
    }
}
```


### Advanced Pool Patterns

#### Reactive Pool Management Pattern
```java
// Single pool reference pattern
private final Pool pool;

// Initialize once, inject everywhere
this.pool = clientFactory.getConnectionManager()
    .getOrCreateReactivePool("peegeeq-main", connectionConfig, poolConfig);

// Use across components
this.metrics = new PeeGeeQMetrics(pool, instanceId);
this.healthCheckManager = new HealthCheckManager(pool, timeout, interval);
```

#### Connection Pool Idempotency
```java
// Thread-safe, idempotent pool creation
public Pool getOrCreateReactivePool(String serviceId,
                                   PgConnectionConfig cfg,
                                   PgPoolConfig poolCfg) {
    return pools.computeIfAbsent(serviceId, id -> {
        try {
            Pool pool = createReactivePool(cfg, poolCfg);
            logger.info("Created reactive pool for service '{}'", id);
            return pool;
        } catch (Exception e) {
            logger.error("Failed to create pool for {}: {}", id, e.getMessage());
            pools.remove(id); // Clean up on failure
            throw e;
        }
    });
}
```

#### Backpressure and Circuit Breaking
```java
public class BackpressureManager {
    private final int maxConcurrentRequests;
    private final Duration timeout;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public <T> Future<T> execute(Supplier<Future<T>> operation) {
        if (activeRequests.get() >= maxConcurrentRequests) {
            return Future.failedFuture(new BackpressureException("Too many concurrent requests"));
        }

        activeRequests.incrementAndGet();
        return operation.get()
            .onComplete(ar -> activeRequests.decrementAndGet());
    }
}
```

## 🎛️ System Properties and Runtime Configuration

All Vert.x 5.x optimizations can be controlled via system properties for runtime tuning:

### Core Performance Properties
```bash
# Pool Configuration (Research-Based Optimized Defaults)
-Dpeegeeq.database.pool.max-size=100
-Dpeegeeq.database.pool.shared=true
-Dpeegeeq.database.pool.name=peegeeq-optimized-pool

# Pipelining Configuration (Maximum Throughput)
-Dpeegeeq.database.pipelining.enabled=true
-Dpeegeeq.database.pipelining.limit=256

# Event Loop Optimization (Database-Intensive Workloads)
-Dpeegeeq.database.event.loop.size=16
-Dpeegeeq.database.worker.pool.size=32

# Verticle Scaling (≃ CPU cores)
-Dpeegeeq.verticle.instances=8
```

### Advanced Performance Properties
```bash
# High-Concurrency Scenarios
-Dpeegeeq.database.pool.max-size=200
-Dpeegeeq.database.pool.wait-queue-multiplier=20

# Connection Management
-Dpeegeeq.database.connection.timeout=30000
-Dpeegeeq.database.idle.timeout=600000

# Batch Operations
-Dpeegeeq.database.batch.size=1000
-Dpeegeeq.database.use.event.bus.distribution=true
```

### Environment-Specific Configurations

**Development Environment:**
```bash
java -jar peegeeq-app.jar \
  -Dpeegeeq.database.pool.max-size=20 \
  -Dpeegeeq.database.pipelining.limit=256 \
  -Dpeegeeq.database.event.loop.size=4
```

**Production Environment:**
```bash
java -jar peegeeq-app.jar \
  -Dpeegeeq.database.pool.max-size=100 \
  -Dpeegeeq.database.pipelining.limit=256 \
  -Dpeegeeq.database.event.loop.size=16 \
  -Dpeegeeq.database.worker.pool.size=32
```

**Extreme High-Throughput Environment:**
```bash
java -jar peegeeq-app.jar \
  -Dpeegeeq.database.pool.max-size=200 \
  -Dpeegeeq.database.pool.wait-queue-multiplier=20 \
  -Dpeegeeq.database.pipelining.limit=512 \
  -Dpeegeeq.database.event.loop.size=32 \
  -Dpeegeeq.database.worker.pool.size=64
```

## 📊 Performance Monitoring and Troubleshooting

### Connection Pool Exhaustion Diagnosis

**Common Error Pattern:**
```
io.vertx.core.http.ConnectionPoolTooBusyException: Connection pool reached max wait queue size of 200
```

**Root Cause Analysis:**
1. **Insufficient Pool Size**: Default pool size (32) insufficient for high-concurrency workloads
2. **Small Wait Queue (if bounded)**: Configured wait queue (e.g., 200) too small for burst traffic. Note: Vert.x 5 default is -1 (unbounded).
3. **Pipelining usage**: Pipelining not enabled or not used on a single connection. Set `PgConnectOptions#setPipeliningLimit` and, when you need multiple in-flight commands on the same connection, keep the connection with `withConnection`/`withTransaction`.
4. **Resource Leaks**: Not properly closing connections or clients

**Solution Implementation:**
```java
// Increase pool size based on workload
poolOptions.setMaxSize(100);  // From 32 to 100

// Increase wait queue size significantly
poolOptions.setMaxWaitQueueSize(1000);  // From 200 to 1000

// Use pipelined client for read operations
SqlClient client = getOptimalReadClient();  // Returns pipelined client
```

### Performance Metrics to Monitor

**Critical Metrics:**
- **Pool Utilization**: Should be < 80% under normal load
- **Wait Queue Size**: Should rarely exceed 50% of maximum
- **Connection Acquisition Time**: Should be < 10ms
- **Query Execution Time**: Should be < 50ms for simple operations
- Measure pipelining impact: gains vary by workload and RTT; avoid assuming a fixed multiplier


### Operational Health Checks
```java
// Real health check with actual database query
public Future<Boolean> checkHealth(String serviceId) {
    Pool pool = pools.get(serviceId);
    if (pool == null) return Future.succeededFuture(false);

    return pool.withConnection(conn ->
        conn.query("SELECT 1").execute().map(rs -> true)
    ).recover(err -> {
        logger.warn("Health check failed for {}: {}", serviceId, err.getMessage());
        return Future.succeededFuture(false);
    });
}
```

## Tuning Recommendations

### Phase 1: Foundation (Research-Based Defaults)
1. Start with conservative defaults (pool size 16–32; pipelining limit 256) and measure p95 latency and pool wait.
2. Use connection-level pipelining via `PgConnectOptions#setPipeliningLimit`; to pipeline on a single connection, use `withConnection`/`withTransaction`.
3. Configure shared pools with clear names and monitoring.
4. If you choose to bound the wait queue, set it explicitly (e.g., 5–10x pool size) and monitor back-pressure; Vert.x 5 default is -1 (unbounded).

### Phase 2: Monitoring and Baseline (24-48 hours)
1. **Monitor connection pool metrics** continuously
2. **Track query performance** and identify bottlenecks
3. **Measure throughput** under realistic load patterns
4. **Identify resource utilization** patterns

### Phase 3: Optimization (Based on Metrics)
1. **Adjust pool size** based on actual connection utilization
2. **Tune pipelining limits** based on network latency and proxy configuration
3. **Scale event loop instances** based on CPU utilization patterns
4. **Optimize batch sizes** for bulk operations

### Phase 4: Production Tuning (Continuous)
1. **Work with your DBA** to optimize database-side configuration
2. **Implement circuit breakers** for resilience
3. **Add comprehensive monitoring** and alerting
4. **Regular performance reviews** and optimization cycles

## 🏆 Success Metrics and Validation

### Performance Validation Checklist

**✅ Connection Pool Health:**
- [ ] Pool utilization < 80% under normal load
- [ ] Wait queue size < 50% of maximum
- [ ] Connection acquisition time < 10ms
- [ ] Zero connection pool exhaustion errors

**✅ Throughput Validation:**
- [ ] Bitemporal operations > 500 events/sec
- [ ] Native queue operations > 5,000 events/sec
- [ ] Outbox pattern operations > 2,000 events/sec
- [ ] Batch operations show measurable improvement over individual operations (quantify with your own benchmarks)

**✅ Latency Validation:**
- [ ] P95 query latency < 50ms
- [ ] P99 query latency < 100ms
- [ ] Connection acquisition latency < 10ms
- [ ] End-to-end operation latency < 200ms

**✅ Resource Utilization:**
- [ ] CPU utilization < 70% under normal load
- [ ] Memory usage stable with no leaks
- [ ] Database connection count within limits
- [ ] Event loop utilization balanced across threads

### Troubleshooting Common Issues

**Issue**: `Connection pool reached max wait queue size`
**Solution**: Increase pool size and wait queue multiplier

**Issue**: Poor pipelining performance
**Solution**: Ensure pipelining is enabled via `PgConnectOptions#setPipeliningLimit`. To pipeline multiple commands on a single connection, use `withConnection`/`withTransaction` or a facade that supports pool-level pipelining. Measure results.

**Issue**: High connection acquisition time
**Solution**: Increase pool size or reduce connection timeout

**Issue**: Memory leaks
**Solution**: Ensure proper cleanup of pipelined clients and pools


## Testing Strategies

- Prefer full-stack integration tests with TestContainers; avoid mocks when real infra is available
- Use standardized container image: postgres:15.13-alpine3.20
- Centralize schema initialization; fail fast and read full logs on failures
- Validate transaction retry behavior for 40001 and 40P01

```java
// Minimal TestContainers + Vert.x 5.x Postgres client example
@Test
void integrationTest_withPostgresContainer() {
    try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:15.13-alpine3.20")) {
        pg.start();
        Vertx vertx = Vertx.vertx();
        try {
            PgConnectOptions connect = new PgConnectOptions()
                .setPort(pg.getFirstMappedPort())
                .setHost(pg.getHost())
                .setDatabase(pg.getDatabaseName())
                .setUser(pg.getUsername())
                .setPassword(pg.getPassword());

            PoolOptions poolOpts = new PoolOptions().setMaxSize(8).setMaxWaitQueueSize(80);
            Pool pool = PgBuilder.pool().with(poolOpts).connectingTo(connect).using(vertx).build();

            pool.withConnection(conn -> conn.query("SELECT 1").execute())
                .toCompletionStage().toCompletableFuture().join();
        } finally {
            vertx.close();
        }
    }
}
```

---

# Section 3: Shutdown Coordination Patterns

## Critical Pattern: Graceful Shutdown with Connection Pool Coordination

### 🚨 Problem: Race Condition During Shutdown

During application shutdown, a critical race condition can occur between:
1. **Background message processing** (running on Vert.x event loop threads)
2. **Connection pool closure** (initiated by main thread during shutdown)

This race condition manifests as connection pool errors during shutdown, violating the principle of "Fix the Cause, Not the Symptom."

### Root Cause Analysis

**The Problem Pattern:**
```java
// ❌ PROBLEMATIC: No shutdown coordination
private void moveToDeadLetterQueueReactive(String messageId, int retryCount, String errorMessage) {
    Pool pool = getOrCreateReactivePool();
    if (pool == null) {
        logger.warn("No reactive pool available to move message {} to dead letter queue", messageId);
        return;
    }

    // RACE CONDITION: Pool might be closing while this executes
    pool.withTransaction(client -> {
        // This can fail with "Connection is not active now, current status: CLOSING"
        return client.preparedQuery(insertSql).execute(params);
    })
    .onFailure(error -> {
        // This logs confusing errors during shutdown
        logger.error("Failed to move message {} to dead letter queue: {}", messageId, error.getMessage());
    });
}
```

**Error Manifestation:**
```
11:01:31.938 [vert.x-eventloop-thread-8] ERROR d.mars.peegeeq.outbox.OutboxConsumer -
Failed to move message 3 to dead letter queue: Connection is not active now, current status: CLOSING
```

### The Shutdown Race Condition Timeline

```
Time    Main Thread                     Event Loop Thread
----    -----------                     -----------------
T1      Application shutdown begins     Message processing continues
T2      PgConnectionManager.close()     Background retry logic triggered
T3      Pool status = CLOSING           moveToDeadLetterQueueReactive() called
T4      Connections being closed        pool.withTransaction() attempted
T5      Pool closed                     ❌ "Connection is not active" error
```

### 🎯 Solution: Shutdown Coordination Pattern

**The Fix Pattern:**
```java
// ✅ CORRECT: Proper shutdown coordination
private void moveToDeadLetterQueueReactive(String messageId, int retryCount, String errorMessage) {
    // CRITICAL FIX: Check if consumer is closed before attempting operations
    if (closed.get()) {
        logger.debug("Consumer is closed, skipping dead letter queue operation for message {}", messageId);
        return;
    }

    Pool pool = getOrCreateReactivePool();
    if (pool == null) {
        logger.warn("No reactive pool available to move message {} to dead letter queue", messageId);
        return;
    }

    pool.preparedQuery(selectSql)
        .execute(params)
        .onSuccess(result -> {
            // Double-check if consumer is still active after getting message details
            if (closed.get()) {
                logger.debug("Consumer closed after retrieving message {} details, skipping dead letter queue operation", messageId);
                return;
            }

            // Insert into dead letter queue and update original message in a transaction
            pool.withTransaction(client -> {
                // Triple-check if consumer is still active before starting transaction
                if (closed.get()) {
                    logger.debug("Consumer closed before transaction start for message {}, aborting dead letter queue operation", messageId);
                    return Future.failedFuture(new IllegalStateException("Consumer is closed"));
                }

                return client.preparedQuery(insertSql).execute(insertParams)
                    .compose(insertResult -> {
                        return client.preparedQuery(updateSql).execute(updateParams);
                    });
            })
            .onFailure(error -> {
                // CRITICAL FIX: Handle shutdown errors gracefully
                if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                    error.getMessage().contains("Pool closed") ||
                                    error.getMessage().contains("CLOSING"))) {
                    logger.debug("Pool/connection closed during shutdown for message {} - this is expected during shutdown", messageId);
                } else {
                    logger.error("Failed to move message {} to dead letter queue: {}", messageId, error.getMessage());
                }
            });
        })
        .onFailure(error -> {
            // CRITICAL FIX: Handle pool/connection errors during shutdown gracefully
            if (closed.get() && (error.getMessage().contains("Connection is not active") ||
                                error.getMessage().contains("Pool closed") ||
                                error.getMessage().contains("CLOSING"))) {
                logger.debug("Pool/connection closed during shutdown for message {} details retrieval - this is expected during shutdown", messageId);
            } else {
                logger.error("Failed to retrieve message {} details for dead letter queue: {}", messageId, error.getMessage());
            }
        });
}
```

### Implementation Pattern: Multi-Level Shutdown Checks

**Level 1: Early Detection**
```java
// Check at method entry
if (closed.get()) {
    logger.debug("Consumer is closed, skipping operation for message {}", messageId);
    return;
}
```

**Level 2: After Async Operations**
```java
// Check after each async operation
.onSuccess(result -> {
    if (closed.get()) {
        logger.debug("Consumer closed after async operation, aborting");
        return;
    }
    // Continue processing...
});
```

**Level 3: Before Critical Sections**
```java
// Check before starting transactions
pool.withTransaction(client -> {
    if (closed.get()) {
        return Future.failedFuture(new IllegalStateException("Consumer is closed"));
    }
    // Perform transaction...
});
```

**Level 4: Graceful Error Handling**
```java
.onFailure(error -> {
    // Distinguish between shutdown errors and real errors
    if (closed.get() && isShutdownRelatedError(error)) {
        logger.debug("Expected shutdown error: {}", error.getMessage());
    } else {
        logger.error("Unexpected error: {}", error.getMessage());
    }
});
```

### Established Pattern from Codebase

This pattern follows the established shutdown coordination pattern found in `PgNativeQueueConsumer.java`:

```java
// Established pattern from PgNativeQueueConsumer
private void releaseExpiredLocks() {
    // Critical fix: Don't attempt operations if consumer is closed
    if (closed.get()) {
        return;
    }

    pool.preparedQuery(updateSql)
        .execute(params)
        .onFailure(error -> {
            // Critical fix: Handle "Pool closed" errors during shutdown gracefully
            if (closed.get() && error.getMessage().contains("Pool closed")) {
                logger.debug("Pool closed during shutdown - this is expected");
            } else {
                logger.warn("Failed operation: {}", error.getMessage());
            }
        });
}
```

### Complete Implementation Example

**OutboxConsumer.java - All Methods Updated:**

```java
public class OutboxConsumer<T> implements MessageConsumer<T> {
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private void markMessageCompleted(String messageId) {
        // Check if consumer is closed before attempting completion operation
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping completion operation for message {}", messageId);
            return;
        }

        Pool pool = getOrCreateReactivePool();
        if (pool == null) {
            logger.warn("No reactive pool available to mark message {} as completed", messageId);
            return;
        }

        pool.preparedQuery(sql).execute(params)
            .onFailure(error -> {
                // Handle pool/connection errors during shutdown gracefully
                if (closed.get() && isShutdownRelatedError(error)) {
                    logger.debug("Pool/connection closed during shutdown for message {} completion - this is expected during shutdown", messageId);
                } else {
                    logger.warn("Failed to mark message {} as completed: {}", messageId, error.getMessage());
                }
            });
    }

    private void handleMessageFailureWithRetry(String messageId, String errorMessage) {
        // Check if consumer is closed before attempting failure handling
        if (closed.get()) {
            logger.debug("Consumer is closed, skipping failure handling for message {}", messageId);
            return;
        }

        // ... similar pattern for all database operations
    }

    private boolean isShutdownRelatedError(Throwable error) {
        String message = error.getMessage();
        return message != null && (
            message.contains("Connection is not active") ||
            message.contains("Pool closed") ||
            message.contains("CLOSING")
        );
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Shutdown sequence...
        }
    }
}
```

### Testing the Fix

**Before Fix:**
```
11:01:31.938 [vert.x-eventloop-thread-8] ERROR d.mars.peegeeq.outbox.OutboxConsumer -
Failed to move message 3 to dead letter queue: Connection is not active now, current status: CLOSING
```

**After Fix:**
```
11:24:06.023 [vert.x-eventloop-thread-5] ERROR d.mars.peegeeq.outbox.OutboxConsumer -
Failed to move message 3 to dead letter queue: Consumer is closed
```

**Key Improvements:**
1. **Clear Error Message**: "Consumer is closed" instead of confusing connection pool status
2. **Controlled Failure**: Error is generated by our shutdown coordination logic, not by connection pool race condition
3. **Expected Behavior**: The error now represents expected shutdown behavior rather than an unexpected race condition

### Best Practices for Shutdown Coordination

#### 1. **Use AtomicBoolean for Thread-Safe State**
```java
private final AtomicBoolean closed = new AtomicBoolean(false);

// Thread-safe check
if (closed.get()) {
    return;
}

// Thread-safe state change
if (closed.compareAndSet(false, true)) {
    // Perform shutdown
}
```

#### 2. **Check State at Multiple Levels**
- **Method Entry**: Prevent starting new operations
- **After Async Operations**: Handle operations that started before shutdown
- **Before Critical Sections**: Prevent resource-intensive operations
- **In Error Handlers**: Distinguish shutdown errors from real errors

#### 3. **Graceful Error Handling**
```java
.onFailure(error -> {
    if (closed.get() && isShutdownRelatedError(error)) {
        // Expected during shutdown - log at debug level
        logger.debug("Expected shutdown error: {}", error.getMessage());
    } else {
        // Unexpected error - log at error level
        logger.error("Unexpected error: {}", error.getMessage());
    }
});
```

#### 4. **Consistent Error Detection**
```java
private boolean isShutdownRelatedError(Throwable error) {
    String message = error.getMessage();
    return message != null && (
        message.contains("Connection is not active") ||
        message.contains("Pool closed") ||
        message.contains("CLOSING") ||
        message.contains("Consumer is closed")
    );
}
```

### When to Apply This Pattern

**Apply shutdown coordination when:**
- Background processing continues during application shutdown
- Database operations are performed on event loop threads
- Connection pools are managed by the application lifecycle
- Race conditions between shutdown and background operations are possible

**Key Indicators:**
- Errors containing "Connection is not active"
- Errors containing "Pool closed" or "CLOSING"
- Errors occurring specifically during test/application shutdown
- Race conditions between main thread shutdown and event loop operations

### Performance Impact

**Minimal Performance Impact:**
- `AtomicBoolean.get()` is a lightweight operation


- Checks are only performed at strategic points
- No impact on normal operation performance
- Prevents resource waste from failed operations during shutdown

**Benefits:**
- Eliminates confusing error messages during shutdown
- Prevents unnecessary resource usage during shutdown
- Improves application shutdown reliability
- Follows "Fail Fast, Fail Clearly" principle

---

## Conclusion

This comprehensive guide provides both the modern composable Future patterns and performance optimization techniques essential for Vert.x 5.x development. The PeeGeeQ project demonstrates how these patterns work together to create maintainable, high-performance reactive applications.

### Key Takeaways

## Production Deployment Checklist

- [ ] Single Vert.x instance per service; never create Vertx.vertx() in factories
- [ ] PgBuilder.pool() with clear PoolOptions and maxWaitQueueSize configured
- [ ] Use Pool.withTransaction(...) for transactional code; TransactionPropagation.CONTEXT for nesting
- [ ] Health checks must query the database (e.g., SELECT 1), not return synthetic OKs
- [ ] Use Vert.x timers; run blocking work via executeBlocking; avoid ScheduledExecutorService
- [ ] Proper SSL configuration and secret handling; do not log passwords or include in toString()
- [ ] Async close for pools and clients; cancel timers before starting shutdown
- [ ] Integration tests use TestContainers with postgres:15.13-alpine3.20
- [ ] Establish performance baselines and add CI regression alerts

---


1. **Use Composable Patterns**: Modern `.compose()`, `.onSuccess()`, `.onFailure()` patterns provide better readability and maintainability than callback-style programming.

2. **Optimize for Performance**: Research-based configuration with proper pool sizing, pipelining, and shared resources can yield significant improvements — quantify with your own benchmarks.

3. **Monitor and Tune**: Continuous monitoring and tuning based on real metrics ensures optimal performance in production environments.

4. **Follow Best Practices**: Proper resource management, error handling, and architectural patterns are essential for robust reactive applications.

The patterns and optimizations demonstrated here serve as a reference for any Vert.x 5.x application requiring high performance and maintainability.

## 📚 Additional Resources

- **Vert.x 5.x PostgreSQL Client Documentation**: Official performance guidelines
- **Clement Escoffier's Performance Articles**: Advanced optimization techniques
- **PeeGeeQ Performance Examples**: Real-world implementation patterns
- **PostgreSQL Performance Tuning**: Database-side optimization guides


---

        .onComplete(ar -> started = false);
}
```

### 3. **Modern Future Composition**

```java
// Use .compose(), .onSuccess(), .onFailure() instead of callbacks
return pool.withConnection(conn ->
    conn.query("SELECT 1").execute()
        .compose(rs -> processResults(rs))
        .onSuccess(result -> logger.info("Success: {}", result))
        .onFailure(err -> logger.error("Failed", err))
);
```

### 4. **Proper Resource Cleanup**

```java
// Async close with Future aggregation
public Future<Void> closeAsync() {
    var futures = pools.keySet().stream()
        .map(this::closePoolAsync)
        .toList();

    pools.clear();
    return Future.all(futures)
        .onSuccess(v -> logger.info("All pools closed"))
        .mapEmpty();
}

// AutoCloseable wrapper for compatibility
@Override
public void close() {
    try {
        closeAsync().toCompletionStage().toCompletableFuture().get(5, SECONDS);
    } catch (Exception e) {
        logger.error("Error during close", e);
    }
}
```

## Configuration Best Practices

### 1. **Connection Configuration**

```java
public final class PgConnectionConfig {
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password; // Consider char[] for security
    private final SslMode sslMode; // Vert.x native SSL
    private final Duration connectTimeout;
    private final int reconnectAttempts;

    // Remove JDBC artifacts like getJdbcUrl()
    @Deprecated(forRemoval = true)
    public String getJdbcUrl() {
        throw new UnsupportedOperationException("Not used in Vert.x reactive mode");
    }
}
```

### 2. **Pool Configuration**

```java
public final class PgPoolConfig {
    private final int maxSize; // Match Vert.x API names
    private final int maxWaitQueueSize; // Critical for backpressure
    private final Duration idleTimeout;
    private final Duration connectionTimeout;
    private final boolean shared;

    // Remove JDBC-only fields
    // private final int minimumIdle; // Not used by Vert.x
    // private final Duration maxLifetime; // Not used by Vert.x
}
```

---

## Health Check Implementation

```java
// Real health check with actual database query
public Future<Boolean> checkHealth(String serviceId) {
    Pool pool = pools.get(serviceId);
    if (pool == null) return Future.succeededFuture(false);

    return pool.withConnection(conn ->
        conn.query("SELECT 1").execute().map(rs -> true)
    ).recover(err -> {
        logger.warn("Health check failed for {}: {}", serviceId, err.getMessage());
        return Future.succeededFuture(false);
    });
}
```

---

## Error Handling Patterns

### 1. **Fail Fast Validation**

```java
// Validate inputs early
private static void validate(String clientId, PgConnectionConfig conn, PgPoolConfig pool) {
    if (clientId == null || clientId.isBlank())
        throw new IllegalArgumentException("clientId must be non-blank");
    Objects.requireNonNull(conn, "connectionConfig");
    Objects.requireNonNull(pool, "poolConfig");
}
```

### 2. **Graceful Degradation**

```java
// Handle failures without breaking periodic tasks
vertx.setPeriodic(intervalMs, id -> {
    vertx.executeBlocking(() -> {
        try {
            performMaintenanceTask();
            return null;
        } catch (Exception e) {
            logger.warn("Maintenance task failed", e);
            return null; // Don't fail the periodic timer
        }
    });
});
```



---

## Source: docs/guidelines/VERTX_MULTI-STATEMENT_SQL_BUG_ANALYSIS.md


# Vert.x Multi-Statement SQL Bug - Critical Analysis & Remediation

**Date:** November 30, 2025  
**Severity:** CRITICAL (P0)  
**Status:** FIXED  
**Author:** Technical Analysis Team

---

## Executive Summary

A critical bug was discovered in the PeeGeeQ database setup code where **Vert.x PostgreSQL client silently ignores all SQL statements after the first statement** in multi-statement SQL strings. This resulted in incomplete database schema creation with 30+ missing database objects (indexes, triggers, functions) per template execution.

**Impact:** Minimal - Production was protected by defensive verification logic.  
**Root Cause:** Vert.x PostgreSQL client limitation + inadequate test coverage.  
**Resolution:** Split all multi-statement SQL files into single-statement files with manifest-based execution.

---

## Technical Details

### Tenant isolation adaptation

This document was imported from the sibling PeeGeeQ repository, where historical examples used fixed schemas such as `peegeeq` and `bitemporal`.

For `peegee-cache`, those names must be treated as historical context only. The authoritative rule is tenant isolation:

- do not hardcode shared schema names in SQL or Java code
- parameterize schema creation and verification using tenant-configured schema names
- qualify notification channels and object lookups with the active tenant schema

All examples below should therefore be read as tenant-aware patterns, using placeholders such as `{queueSchema}` and `{eventSchema}` rather than global shared schemas.

### The Vert.x Limitation

```java
// What we THOUGHT happened:
String multiStatementSQL = """
    CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
    CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";
    CREATE SCHEMA IF NOT EXISTS {queueSchema};
    -- ... 17 more statements
""";
pool.query(multiStatementSQL).execute(); // Expected: All 20 statements execute

// What ACTUALLY happened:
// Vert.x executes ONLY the first statement:
// ✅ CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
// ❌ All other 19 statements SILENTLY IGNORED
```

This is a **documented limitation** of Vert.x Reactive PostgreSQL Client:
- Official docs: "Extended query protocol supports only ONE statement per execution"
- No error thrown
- No warning logged
- **Silent failure by design**

### Affected Files

**Before the fix** (3 multi-statement files):

1. **`peegeeq-template.sql`** (196 lines)
   - 32 SQL statements
   - Only statement 1 executed: `CREATE EXTENSION "uuid-ossp"`
    - **Missing:** 1 extension, tenant schema creation, tenant template tables, indexes, and consumer-group support objects

2. **`create-queue-table.sql`** (50 lines)
   - 8 SQL statements
   - Only statement 1 executed: `CREATE TABLE {queueName}`
   - **Missing:** 5 indexes, 1 function, 1 trigger

3. **`create-eventstore-table.sql`** (120 lines)
   - 13 SQL statements
   - Only statement 1 executed: `CREATE TABLE {tableName}`
   - **Missing:** 10 indexes, 1 function, 1 trigger

**After the fix** (52 single-statement files organized in 3 directories):

```
sql-templates/
├── base/
│   ├── .manifest (lists execution order)
│   ├── 01a-extension-uuid.sql
│   ├── 01b-extension-pgstat.sql
│   ├── 03-schema-{queueSchema}.sql
│   ├── 04-schema-{eventSchema}.sql
│   ├── 05-set-search-path.sql
│   ├── 06-drop-queue-template.sql
│   ├── 07-create-queue-template.sql
│   ├── 08-drop-eventstore-template.sql
│   ├── 09-create-eventstore-template.sql
│   ├── 07a-queue-index-topic.sql
│   ├── ... (22 more index files)
│   ├── 08a-consumer-table-topics.sql
│   ├── ... (9 more consumer table/index files)
│   └── [31 files total]
├── eventstore/
│   ├── .manifest
│   ├── 01-create-table.sql
│   ├── 02a-index-validtime.sql
│   ├── ... (10 more index files)
│   ├── 03-create-function.sql
│   └── 04-create-trigger.sql
│   └── [13 files total]
└── queue/
    ├── .manifest
    ├── 01-create-table.sql
    ├── 02a-index-topic.sql
    ├── ... (5 more index files)
    ├── 03-create-function.sql
    └── 04-create-trigger.sql
    └── [8 files total]
```

---

## Why Production Was NOT Affected

### Defense-in-Depth Architecture

Production code in `PeeGeeQDatabaseSetupService.java` has **multiple safety mechanisms** that prevented this bug from causing production failures:

#### 1. **Explicit Template Verification** (Lines 247-258)

```java
return templateProcessor.applyTemplateReactive(connection, "base", Map.of())
    .compose(v -> {
        // CRITICAL SAFEGUARD: Verify templates actually exist in the configured tenant schemas
        logger.error("🔧🔍 Verifying templates exist in configured tenant schemas...");
        return verifyTemplatesExist(connection, queueSchema, eventSchema);
    })
    .recover(err -> {
        if (isExtensionPermissionError(err) || err.getMessage().contains("Templates not found")) {
            logger.warn("Base template verification failed. Falling back to minimal tenant-local core schema");
            return applyMinimalCoreSchemaReactive(connection, queueSchema).map(Boolean.FALSE);
        }
        return Future.failedFuture(err);
    })
```

**Impact:** If template tables (`queue_template`, `event_store_template`) were missing, setup would **fail fast** with clear error message rather than silently proceeding.

#### 2. **Template Existence Check** (Lines 357-378)

```java
private Future<Void> verifyTemplatesExist(SqlConnection connection, String queueSchema, String eventSchema) {
    String checkTemplatesSQL = """
        SELECT 
            EXISTS (SELECT 1 FROM information_schema.tables 
                    WHERE table_schema = $1 AND table_name = 'queue_template') as queue_exists,
            EXISTS (SELECT 1 FROM information_schema.tables 
                    WHERE table_schema = $2 AND table_name = 'event_store_template') as event_store_exists
        """;
    
    return connection.preparedQuery(checkTemplatesSQL).execute(Tuple.of(queueSchema, eventSchema))
        .compose(rowSet -> {
            boolean queueExists = row.getBoolean("queue_exists");
            boolean eventStoreExists = row.getBoolean("event_store_exists");
            
            if (!queueExists || !eventStoreExists) {
                String msg = String.format(
                    "Templates not found in configured schemas: %s.queue_template=%b, %s.event_store_template=%b. " +
                    "This usually means template creation failed before the schema setup completed.",
                    queueSchema, queueExists, eventSchema, eventStoreExists
                );
                return Future.failedFuture(new IllegalStateException(msg));
            }
            return Future.succeededFuture();
        });
}
```

**Impact:** Production explicitly checks that template tables exist before attempting to create queue/eventstore tables. Missing templates → immediate failure.

#### 3. **Fallback to Minimal Schema** (Lines 259-270)

```java
.recover(err -> {
    if (isExtensionPermissionError(err) || err.getMessage().contains("Templates not found")) {
        logger.warn("Base template verification failed. Falling back to minimal tenant-local core schema");
        return applyMinimalCoreSchemaReactive(connection, queueSchema).map(Boolean.FALSE);
    }
    return Future.failedFuture(err);
})
```

**Impact:** If template creation failed, system would fall back to creating core operational tables (`queue_messages`, `outbox`, `dead_letter_queue`) without using templates. This provides **graceful degradation**.

#### 4. **Dependency Check Before Queue/EventStore Creation** (Lines 267-289)

```java
.compose(baseApplied -> {
    // Only create per-queue tables if base template (with templates) was applied
    if (Boolean.TRUE.equals(baseApplied)) {
        // Proceed with queue creation using templates
    } else {
        // CRITICAL: If queues were requested but template failed, this is an error!
        if (!request.getQueues().isEmpty()) {
            String errorMsg = String.format(
                "Cannot create queues [%s] because database template creation failed. " +
                "Queue tables require %s.queue_template which could not be created.",
                queueSchema,
                queueNames
            );
            return Future.failedFuture(new IllegalStateException(errorMsg));
        }
    }
})
```

**Impact:** System explicitly checks if base templates were successfully applied before attempting to create derived tables. **No silent failures.**

### Why Tests Passed Despite the Bug

Tests in `SqlTemplateProcessorTest.java` were **inadequately designed** and passed due to:

#### 1. **PostgreSQL Base Image Pre-seeding**
```java
verifyExtensionExists("pg_stat_statements");  // ✅ PASSED
// But statement 2 (CREATE EXTENSION "pg_stat_statements") was NEVER executed!
// Extension already existed in postgres:15.13-alpine3.20 base image
```

#### 2. **Idempotent SQL Masking**
```sql
CREATE SCHEMA IF NOT EXISTS {queueSchema};  -- Statement 3: NEVER executed
CREATE SCHEMA IF NOT EXISTS {eventSchema};  -- Statement 4: NEVER executed
```
Tests checked schema existence but couldn't distinguish between "created by template" vs "already existed".

#### 3. **Test Execution Order Dependency**
```java
@Order(1)
void testApplyBaseTemplate() {
    // Applied base template (only statement 1 ran)
    verifyTableExists(queueSchema, "queue_template");  // ✅ PASSED but WHY?
}

@Order(2)  
void testCreateQueueTableTemplate() {
    // Applied base template AGAIN
    // Then applied queue template which created test_orders table
}
```

**Accidental Success:** Later tests re-applied base template, and the subsequent "queue" template's **first statement** (`CREATE TABLE`) successfully created tables, making earlier verification checks pass retroactively due to test order.

#### 4. **Incomplete Assertions**
Tests verified:
- ✅ 2 extensions exist
- ✅ 2 schemas exist  
- ✅ 2 template tables exist
- ✅ **Only 4 indexes** (out of 30+ total)

Tests **did NOT verify:**
- ❌ Consumer group tables (5 missing)
- ❌ Consumer group indexes (5 missing)
- ❌ Template indexes (11 missing)
- ❌ Queue-specific indexes (5 per queue)
- ❌ EventStore-specific indexes (10 per event store)
- ❌ Notification functions (2 missing)
- ❌ Notification triggers (2 missing)

#### 5. **No Integration Tests**
Tests only verified `SqlTemplateProcessor` in isolation. They **never tested**:
- Full `PeeGeeQDatabaseSetupService.createCompleteSetup()` flow
- The `verifyTemplatesExist()` safeguard
- The fallback to minimal schema
- Error handling when templates are missing

---

## Timeline of Discovery

### Initial Symptom (Nov 30, 2025 - Morning)
- Integration tests for event store setup were failing intermittently
- Error: `relation "{eventSchema}.event_store_template" does not exist`
- Initially suspected: Race condition, transaction isolation issues

### Investigation Phase (Nov 30, 2025 - Midday)
1. Verified template files existed in resources
2. Added extensive logging to `SqlTemplateProcessor`
3. Discovered: Only first SQL statement in each file was executing
4. Hypothesis: Vert.x limitation on multi-statement SQL

### Web Research Phase (Nov 30, 2025 - Afternoon)
- Confirmed Vert.x documentation: Extended query protocol = one statement per execute
- Verified behavior with test database: Only first statement executed, rest silently ignored
- No PostgreSQL limitation - this is specifically a Vert.x Reactive SQL Client design decision

### Root Cause Confirmed (Nov 30, 2025 - Late Afternoon)
- Analyzed `SqlTemplateProcessor.java` line 98-103
- Confirmed: Single `.query(sql).execute()` call per template file
- Each file contained 2-32 SQL statements
- **Only first statement per file actually executed**

### Resolution Phase (Nov 30, 2025 - Evening)
1. Split all multi-statement files into 52 single-statement files
2. Created manifest files to define execution order
3. Updated `SqlTemplateProcessor` to load and execute files sequentially from directories
4. Updated all test references from file names to directory names
5. All 7 tests passing with complete schema creation verified

---

## Remediation Actions

### 1. Code Changes (COMPLETED)

#### A. SqlTemplateProcessor.java
**Before:**
```java
private String loadTemplate(String templateName) {
    // Load single SQL file
    InputStream is = getClass().getClassLoader()
        .getResourceAsStream("db/templates/" + templateName);
    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
}
```

**After:**
```java
private List<String> loadTemplateFiles(String templateName) {
    // Check if templateName is a directory
    Path templateDir = Paths.get("db/templates/" + templateName);
    
    // Read .manifest file to get execution order
    List<String> fileNames = Files.readAllLines(manifestPath);
    
    // Load each SQL file individually
    List<String> sqlStatements = new ArrayList<>();
    for (String fileName : fileNames) {
        String sql = Files.readString(templateDir.resolve(fileName));
        sqlStatements.add(sql);
    }
    return sqlStatements;
}

public Future<Void> applyTemplateReactive(SqlConnection connection, String templateName, Map<String, String> params) {
    List<String> sqlStatements = loadTemplateFiles(templateName);
    
    // Execute each statement sequentially
    Future<Void> chain = Future.succeededFuture();
    for (int i = 0; i < sqlStatements.size(); i++) {
        final int index = i;
        chain = chain.compose(v -> {
            String sql = performSubstitutions(sqlStatements.get(index), params);
            return connection.query(sql).execute().mapEmpty();
        });
    }
    return chain;
}
```

**Impact:** Now executes ALL statements in correct order, one at a time.

#### B. File Structure Changes

Created 52 new single-statement SQL files organized in 3 directories:
- `base/` - 31 files (extensions, schemas, template tables, indexes, consumer tables)
- `eventstore/` - 13 files (table, indexes, function, trigger)
- `queue/` - 8 files (table, indexes, function, trigger)

Each directory has a `.manifest` file listing execution order:
```
# base/.manifest
01a-extension-uuid.sql
01b-extension-pgstat.sql
03-schema-{queueSchema}.sql
04-schema-{eventSchema}.sql
# ... (27 more lines)
```

#### C. Test Updates

Updated `SqlTemplateProcessorTest.java` - all template references changed:
- `"peegeeq-template.sql"` → `"base"`
- `"create-queue-table.sql"` → `"queue"`
- `"create-eventstore-table.sql"` → `"eventstore"`

**Result:** All 7 tests passing, complete schema verification.

### 2. Test Improvements (REQUIRED)

The following improvements are **mandatory** to prevent similar issues:

#### A. Comprehensive Object Verification

**Current test verifications** (inadequate):
```java
@Test
void testApplyBaseTemplate() {
    verifyExtensionExists("uuid-ossp");
    verifyExtensionExists("pg_stat_statements");
    verifySchemaExists(queueSchema);
    verifySchemaExists(eventSchema);
    verifyTableExists(queueSchema, "queue_template");
    verifyTableExists(eventSchema, "event_store_template");
    
    // Verifies only 4 indexes out of 30+
    verifyIndexExists("idx_queue_template_topic_visible");
    verifyIndexExists("idx_queue_template_lock");
    verifyIndexExists("idx_queue_template_status");
    verifyIndexExists("idx_queue_template_correlation_id");
    // ❌ MISSING: 26+ other indexes
    // ❌ MISSING: Consumer group tables
    // ❌ MISSING: Consumer group indexes
}
```

**Required improvements:**
```java
@Test
void testApplyBaseTemplate() {
    // 1. Verify extensions
    verifyExtensionExists("uuid-ossp");
    verifyExtensionExists("pg_stat_statements");
    
    // 2. Verify configured tenant schemas
    verifySchemaExists(queueSchema);
    verifySchemaExists(eventSchema);
    
    // 3. Verify template tables with ALL columns
    verifyTableExists(queueSchema, "queue_template");
    verifyTableHasColumns(queueSchema, "queue_template", 
        "id", "topic", "payload", "visible_at", "created_at", 
        "lock_id", "lock_until", "retry_count", "max_retries", 
        "status", "headers", "error_message", "correlation_id", 
        "message_group", "priority");
    
    verifyTableExists(eventSchema, "event_store_template");
    verifyTableHasColumns(eventSchema, "event_store_template",
        "id", "event_id", "event_type", "valid_time", "transaction_time",
        "payload", "headers", "version", "previous_version_id", 
        "is_correction", "correction_reason", "correlation_id", 
        "aggregate_id", "created_at");
    
    // 4. Verify ALL template indexes (15 total)
    verifyIndexExists("idx_queue_template_topic_visible");
    verifyIndexExists("idx_queue_template_lock");
    verifyIndexExists("idx_queue_template_status");
    verifyIndexExists("idx_queue_template_correlation_id");
    verifyIndexExists("idx_queue_template_priority");
    verifyIndexExists("idx_queue_template_message_group");
    verifyIndexExists("idx_queue_template_created_at");
    verifyIndexExists("idx_queue_template_visible_at");
    verifyIndexExists("idx_queue_template_retry_count");
    verifyIndexExists("idx_eventstore_template_validtime");
    verifyIndexExists("idx_eventstore_template_txtime");
    verifyIndexExists("idx_eventstore_template_eventtype");
    verifyIndexExists("idx_eventstore_template_aggregate");
    verifyIndexExists("idx_eventstore_template_correlation");
    verifyIndexExists("idx_eventstore_template_headers");
    
    // 5. Verify consumer group tables in the configured queue schema
    verifyTableExists(queueSchema, "outbox_topics");
    verifyTableExists(queueSchema, "outbox_topic_subscriptions");
    verifyTableExists(queueSchema, "outbox_consumer_groups");
    verifyTableExists(queueSchema, "processed_ledger");
    verifyTableExists(queueSchema, "consumer_group_index");
    
    // 6. Verify consumer group indexes (5 indexes)
    verifyIndexExists("idx_topic_subscriptions_active");
    verifyIndexExists("idx_topic_subscriptions_heartbeat");
    verifyIndexExists("idx_outbox_consumer_groups_group_status");
    verifyIndexExists("idx_processed_ledger_time");
    verifyIndexExists("idx_consumer_group_index_topic");
    
    // 7. Count total objects to ensure completeness in the configured queue schema
    int totalIndexes = countIndexesInSchema(queueSchema);
    assertEquals(15, totalIndexes, "Should have exactly 15 indexes in the configured queue schema");
    
    int totalTables = countTablesInSchema(queueSchema);
    assertEquals(6, totalTables, "Should have exactly 6 tables in the configured queue schema");
}

@Test
void testCreateQueueTableTemplate() {
    // Verify queue table
    verifyTableExists("public", "test_orders");
    
    // Verify ALL 5 queue indexes
    verifyIndexExists("idx_test_orders_topic_visible");
    verifyIndexExists("idx_test_orders_lock");
    verifyIndexExists("idx_test_orders_status");
    verifyIndexExists("idx_test_orders_correlation_id");
    verifyIndexExists("idx_test_orders_priority");
    
    // Verify notification function
    verifyFunctionExists("public", "notify_test_orders_changes");
    
    // Verify notification trigger
    verifyTriggerExists("trigger_test_orders_notify");
    
    // Verify trigger actually works
    testQueueNotificationTrigger("test_orders");
}

@Test
void testCreateEventStoreTableTemplate() {
    // Verify event store table
    verifyTableExists("public", "test_events");
    
    // Verify ALL 10 event store indexes
    verifyIndexExists("idx_test_events_validtime");
    verifyIndexExists("idx_test_events_txtime");
    verifyIndexExists("idx_test_events_eventtype");
    verifyIndexExists("idx_test_events_aggregate");
    verifyIndexExists("idx_test_events_correlation");
    verifyIndexExists("idx_test_events_version");
    verifyIndexExists("idx_test_events_correction");
    verifyIndexExists("idx_test_events_created");
    verifyIndexExists("idx_test_events_eventid");
    verifyIndexExists("idx_test_events_headers");
    
    // Verify notification function
    verifyFunctionExists("public", "notify_test_events_changes");
    
    // Verify notification trigger
    verifyTriggerExists("trigger_test_events_notify");
    
    // Verify trigger actually works
    testEventStoreNotificationTrigger("test_events");
}
```

#### B. Add Integration Test for Full Setup Flow

**New test required:**
```java
@Test
@Order(8)
void testCompleteSetupFlow() throws Exception {
    logger.info("=== Testing Complete Setup Flow (Integration) ===");
    
    // Create a test database
    String testDb = "integration_test_" + System.currentTimeMillis();
    DatabaseConfig dbConfig = DatabaseConfig.builder()
        .host("localhost")
        .port(postgres.getFirstMappedPort())
        .databaseName(testDb)
        .username(postgres.getUsername())
        .password(postgres.getPassword())
        .schema("public")
        .build();
    
    // Create setup request with queues and event stores
    DatabaseSetupRequest request = DatabaseSetupRequest.builder()
        .setupId("integration-test-setup")
        .databaseConfig(dbConfig)
        .queues(List.of(
            QueueConfig.builder()
                .queueName("test_queue_1")
                .maxRetries(3)
                .build(),
            QueueConfig.builder()
                .queueName("test_queue_2")
                .maxRetries(5)
                .build()
        ))
        .eventStores(List.of(
            EventStoreConfig.builder()
                .eventStoreName("test_store_1")
                .tableName("test_events_1")
                .notificationPrefix("test_store_1")
                .build()
        ))
        .build();
    
    // Execute complete setup using PeeGeeQDatabaseSetupService
    PeeGeeQDatabaseSetupService setupService = new PeeGeeQDatabaseSetupService();
    
    DatabaseSetupResult result = setupService.createCompleteSetup(request)
        .get(30, TimeUnit.SECONDS);
    
    // Verify setup result
    assertEquals("integration-test-setup", result.getSetupId());
    assertEquals(DatabaseSetupStatus.ACTIVE, result.getStatus());
    assertEquals(2, result.getQueueFactories().size());
    assertEquals(1, result.getEventStores().size());
    
    // Connect to the new database and verify schema
    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s",
        dbConfig.getHost(), dbConfig.getPort(), testDb);
    
    try (Connection conn = DriverManager.getConnection(jdbcUrl, 
            dbConfig.getUsername(), dbConfig.getPassword())) {
        
        // Verify base templates exist
        verifyTableExistsInConnection(conn, queueSchema, "queue_template");
        verifyTableExistsInConnection(conn, eventSchema, "event_store_template");
        
        // Verify queue tables exist with all indexes
        verifyTableExistsInConnection(conn, "public", "test_queue_1");
        verifyTableExistsInConnection(conn, "public", "test_queue_2");
        verifyIndexExistsInConnection(conn, "idx_test_queue_1_topic_visible");
        verifyIndexExistsInConnection(conn, "idx_test_queue_2_topic_visible");
        
        // Verify event store table exists with all indexes
        verifyTableExistsInConnection(conn, "public", "test_events_1");
        verifyIndexExistsInConnection(conn, "idx_test_events_1_validtime");
        verifyIndexExistsInConnection(conn, "idx_test_events_1_txtime");
        
        // Verify triggers exist and work
        verifyTriggerExistsInConnection(conn, "trigger_test_queue_1_notify");
        verifyTriggerExistsInConnection(conn, "trigger_test_events_1_notify");
        
        // Test actual functionality
        testQueueInsertAndRetrieve(conn, "test_queue_1");
        testEventStoreInsertAndQuery(conn, "test_events_1");
    }
    
    // Cleanup
    setupService.destroySetup("integration-test-setup").get();
    
    logger.info("=== Complete Setup Flow Integration Test Passed ===");
}
```

#### C. Add Object Count Verification

**New helper methods:**
```java
private int countTablesInSchema(String schemaName) throws SQLException {
    String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?";
    try (var stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, schemaName);
        var rs = stmt.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}

private int countIndexesInSchema(String schemaName) throws SQLException {
    String sql = """
        SELECT COUNT(*) FROM pg_indexes 
        WHERE schemaname = ?
        """;
    try (var stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, schemaName);
        var rs = stmt.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}

private int countFunctionsInSchema(String schemaName) throws SQLException {
    String sql = """
        SELECT COUNT(*) FROM information_schema.routines 
        WHERE routine_schema = ?
        """;
    try (var stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, schemaName);
        var rs = stmt.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}

private int countTriggersInSchema(String schemaName) throws SQLException {
    String sql = """
        SELECT COUNT(DISTINCT trigger_name) 
        FROM information_schema.triggers 
        WHERE trigger_schema = ?
        """;
    try (var stmt = connection.prepareStatement(sql)) {
        stmt.setString(1, schemaName);
        var rs = stmt.executeQuery();
        rs.next();
        return rs.getInt(1);
    }
}
```

#### D. Add Regression Test

**New test to prevent recurrence:**
```java
@Test
@Order(9)
void testMultiStatementSQLNotUsed() throws Exception {
    logger.info("=== Testing No Multi-Statement SQL Files Exist ===");
    
    // Verify all template files are single-statement
    Path templatesDir = Paths.get("src/main/resources/db/templates");
    
    List<Path> sqlFiles = Files.walk(templatesDir)
        .filter(p -> p.toString().endsWith(".sql"))
        .filter(p -> !p.toString().endsWith(".manifest"))
        .collect(Collectors.toList());
    
    for (Path sqlFile : sqlFiles) {
        String content = Files.readString(sqlFile);
        
        // Count semicolons outside of quoted strings and comments
        int statementCount = countSQLStatements(content);
        
        assertTrue(statementCount <= 1, 
            String.format("File %s contains %d SQL statements. " +
                "All template files must contain exactly ONE statement to work with Vert.x. " +
                "Split multi-statement files and use .manifest for ordering.",
                sqlFile.getFileName(), statementCount));
    }
    
    logger.info("Verified {} SQL template files - all single-statement", sqlFiles.size());
}

private int countSQLStatements(String sql) {
    // Remove comments
    sql = sql.replaceAll("--[^\n]*", "");
    sql = sql.replaceAll("/\\*.*?\\*/", "");
    
    // Remove string literals
    sql = sql.replaceAll("'[^']*'", "");
    
    // Count semicolons
    int count = 0;
    for (char c : sql.toCharArray()) {
        if (c == ';') count++;
    }
    
    return count;
}
```

### 3. Documentation Updates (REQUIRED)

#### A. Add Warning to SqlTemplateProcessor

```java
/**
 * SQL Template Processor for PeeGeeQ database setup.
 * 
 * CRITICAL LIMITATION: Vert.x Reactive PostgreSQL Client only supports 
 * ONE SQL statement per query execution. Multi-statement SQL strings will 
 * silently execute only the first statement.
 * 
 * SOLUTION: This processor loads SQL files from directories with .manifest 
 * files that define execution order. Each SQL file MUST contain exactly 
 * ONE statement.
 * 
 * @see <a href="https://vertx.io/docs/vertx-pg-client/java/">Vert.x PostgreSQL Client Docs</a>
 */
public class SqlTemplateProcessor {
    // ...
}
```

#### B. Update README with Template Structure

Add to the local README for whichever module owns the SQL templates. In this repository, prefer adding equivalent guidance to a local docs file rather than referencing `peegeeq-db/README.md`, which does not exist here:

```markdown
## SQL Template Structure

### Vert.x Limitation

⚠️ **CRITICAL:** Vert.x Reactive PostgreSQL Client executes only ONE SQL statement per query.
Multi-statement SQL strings will have all statements after the first **silently ignored**.

### Template Organization

All SQL templates are organized in directories with `.manifest` files:

```
sql-templates/
├── base/
│   ├── .manifest          # Defines execution order
│   ├── 01a-extension-uuid.sql
│   ├── 01b-extension-pgstat.sql
│   └── ... (31 files total)
├── eventstore/
│   ├── .manifest
│   └── ... (13 files total)
└── queue/
    ├── .manifest
    └── ... (8 files total)
```

### Rules for SQL Template Files

1. **ONE STATEMENT PER FILE** - Mandatory
2. **Use .manifest files** - Define execution order
3. **No semicolons** - Except at statement end
4. **Test thoroughly** - Verify all objects created

### Adding New Templates

```bash
# 1. Create single-statement SQL file
echo "CREATE INDEX idx_new_index ON table(column);" > new-index.sql

# 2. Add to .manifest in correct order
echo "new-index.sql" >> base/.manifest

# 3. Verify in tests
verifyIndexExists("idx_new_index");
```
```

#### C. Add to local testing guidance

```markdown
## Template Testing Requirements

All SQL template changes MUST include:

1. **Complete object verification** - Test EVERY created object:
   - Extensions
   - Schemas  
   - Tables (with all columns)
   - Indexes (every single one)
   - Functions
   - Triggers

2. **Object count assertions** - Verify total counts match expected:
   ```java
    assertEquals(15, countIndexesInSchema(queueSchema));
    assertEquals(6, countTablesInSchema(queueSchema));
   ```

3. **Integration test** - Test full setup flow including verification

4. **Regression test** - Ensure no multi-statement SQL files exist

5. **Trigger functionality test** - Verify triggers actually execute
```

---

## Action Items

### Immediate (DONE ✅)
- [x] Split all multi-statement SQL files into single-statement files
- [x] Create manifest files for execution order
- [x] Update SqlTemplateProcessor to execute files sequentially
- [x] Update all test references
- [x] Verify all 7 tests pass

### Short-term (This Sprint - REQUIRED)
- [ ] Implement comprehensive object verification in tests (Section 2.A)
- [ ] Add integration test for full setup flow (Section 2.B)
- [ ] Add object count verification helpers (Section 2.C)
- [ ] Add regression test for multi-statement detection (Section 2.D)
- [ ] Update SqlTemplateProcessor documentation (Section 3.A)
- [ ] Update README with template structure (Section 3.B)
- [ ] Update local testing guidance docs (Section 3.C)

### Medium-term (Next Sprint - RECOMMENDED)
- [ ] Add pre-commit hook to detect multi-statement SQL files
- [ ] Create template validation script
- [ ] Add CI/CD check for SQL template structure
- [ ] Review all other SQL usage in codebase for similar issues

### Long-term (Next Quarter - RECOMMENDED)
- [ ] Evaluate alternative SQL execution libraries that support multi-statement
- [ ] Consider migration to stored procedures for complex schema operations
- [ ] Implement database schema version tracking
- [ ] Add monitoring/alerting for incomplete schema deployments

---

## Lessons Learned

### 1. **Library Limitations Are Real**
- Never assume third-party libraries support features that "should work"
- Always verify behavior with actual tests, not just documentation assumptions
- Vert.x limitation is **by design**, not a bug - we must adapt our code

### 2. **Defense-in-Depth Saved Us**
- Production verification logic caught what tests missed
- Multiple layers of checking prevented production impact
- Explicit validation is worth the code complexity

### 3. **Test Quality Matters More Than Test Quantity**
- 7 passing tests gave false confidence
- Tests must verify **complete** behavior, not just partial success
- Integration tests are critical for catching systemic issues

### 4. **Silent Failures Are Dangerous**
- Vert.x executes only first statement with **no error or warning**
- Always log and verify critical operations
- Defensive programming prevents silent bugs

### 5. **Code Reviews Need Context**
- Template files evolved over time without test updates
- Need to review tests alongside production code changes
- Template changes should trigger test verification requirements

---

## Risk Assessment

### Current Risk: **LOW** ✅

**Why:**
- Production code has verification safeguards that prevent failures
- Bug is now completely fixed with single-statement files
- All tests passing with comprehensive schema creation
- No customer impact reported
- No production incidents

### Residual Risks:

1. **Test Coverage Gaps** (Medium)
   - Tests still don't verify all created objects
   - No integration test for complete setup flow
   - **Mitigation:** Implement Section 2 improvements

2. **Future Template Changes** (Low-Medium)
   - Developers might add multi-statement files again
   - **Mitigation:** Add pre-commit hook and CI/CD checks

3. **Other Vert.x SQL Usage** (Low)
   - Same limitation applies to all Vert.x SQL client usage
   - **Mitigation:** Audit codebase for other multi-statement SQL

---

## Conclusion

This bug was a **critical architectural issue** masked by defensive production code and inadequate tests. While production was protected by verification logic, the tests passed for the wrong reasons and provided false confidence.

The fix is **comprehensive and permanent**: 
- Split all SQL into single statements
- Sequential execution with manifest-based ordering
- Complete schema creation now guaranteed

The **real lesson** is about test quality: passing tests mean nothing if they don't verify the complete behavior. The test improvements outlined in Section 2 are **mandatory** to prevent similar issues in the future.

**Production impact:** None - defensive coding saved us.  
**Test impact:** Significant - tests were inadequate and misleading.  
**Long-term impact:** Positive - we now have better architecture and will have better tests.

---

## References

- Vert.x PostgreSQL Client Documentation: https://vertx.io/docs/vertx-pg-client/java/
- PostgreSQL Extended Query Protocol: https://www.postgresql.org/docs/current/protocol-flow.html
- Local coding guidance: `docs/guidelines/pgq-coding-principles.md`
- Local Vert.x patterns guidance: `docs/guidelines/Vertx-5x-Patterns-Guide.md`

---

**Document Version:** 1.0  
**Last Updated:** November 30, 2025  
**Next Review:** December 15, 2025


---

## Source: docs/guidelines/SHUTDOWN_AND_LIFECYCLE_FIXES.md


# PeeGeeQ Shutdown Lifecycle & Concurrency Fixes

Imported reference note:

- this document was copied from the sibling `peegeeq` repository
- the component, test names, and implementation details below refer to that repository unless this repo adds matching runtime code
- in `peegee-cache`, use this as lifecycle-pattern reference material, not as proof that the described fixes were applied here

**Date:** November 28, 2025  
**Component:** sibling `peegeeq-db` (`PeeGeeQManager`)  
**Status:** Imported sibling-repo fix record

## 1. The Issue

During integration testing (`PeeGeeQManagerIntegrationTest`), we encountered two distinct but related failures:

1.  **`java.util.concurrent.TimeoutException`**: Tests were failing because the `manager.close()` method was blocking for up to 5 seconds, fighting with JUnit's own timeout mechanisms.
2.  **`java.util.concurrent.RejectedExecutionException`**: The logs were polluted with stack traces because the Vert.x instance was being closed *before* the Worker Executors had finished their tasks. When those workers tried to report completion back to the Event Loop, the loop was already dead.

### Root Cause Analysis
*   **Incorrect Shutdown Order**: The original implementation closed the DB Connection Pools (Client Factory) *before* the Worker Executors. This created a race condition where workers might try to use closed connections or report to a closed Vert.x instance.
*   **Blocking on Event Loop**: The `AutoCloseable.close()` method was attempting to block (`.get(5, SECONDS)`) to ensure a clean shutdown. However, in an asynchronous environment like Vert.x, blocking the thread (especially if called from a context related to the test runner) caused deadlocks or timeouts.

## 2. The Solution

In the sibling PeeGeeQ codebase, `PeeGeeQManager.java` was refactored to implement a strict, ordered shutdown sequence and non-blocking semantics for legacy interfaces.

### 2.1 Strict Shutdown Order (The "7-Step" Sequence)
We rewrote `closeReactive()` to enforce the following dependency chain using `Future` composition:

1.  **Stop Reactive Components**: Stop accepting new work (`stopReactive()`).
2.  **Run Close Hooks**: Execute any registered external cleanup logic.
3.  **Cancel Timers**: Stop internal maintenance tasks (Metrics, DLQ, Recovery).
4.  **Close Worker Executor**: **CRITICAL STEP**. We now wait for the Worker Executor to close (draining pending tasks) *before* touching the DB pools.
5.  **Close Client Factory**: Close the DB connection pools only after workers are guaranteed idle.
6.  **Close MeterRegistry**: Clean up metrics.
7.  **Close Vert.x**: Finally, close the Vert.x instance (if owned).

### 2.2 "Wait-and-Catch" Pattern
When closing Vert.x from within a test that might share its context, `RejectedExecutionException` is sometimes inevitable (the thread you are standing on disappears). We added specific recovery logic to catch this exception and treat it as a successful shutdown, keeping the logs clean.

### 2.3 Non-Blocking `AutoCloseable`
We changed the `close()` method to be **Fire-and-Forget**:
*   It triggers `closeReactive()` but returns immediately.
*   It logs a warning if called on an Event Loop thread.
*   **Impact**: Tests no longer timeout waiting for `close()`. Instead, the test harness explicitly waits for `closeReactive()` completion in `@AfterEach`.

## 3. Verification

*   **Test Suite**: `PeeGeeQManagerIntegrationTest` passed successfully in the sibling repo.
*   **Performance**: Execution time dropped to ~14s (well within limits).
*   **Logs**: No `RejectedExecutionException` or `TimeoutException` observed in the output.

## 4. Code Reference

**`PeeGeeQManager.java`**

```java
// New Shutdown Sequence
return stopReactive()
    .compose(v -> /* Run Hooks */)
    .compose(v -> /* Cancel Timers */)
    .compose(v -> workerExecutor.close()) // Workers First
    .compose(v -> clientFactory.closeAsync()) // Pools Second
    .compose(v -> /* Close Vert.x */);
```


---

## Source: docs/guidelines/PEEGEEQ_CRASH_RECOVERY_GUIDE.md


# PeeGeeQ Outbox Consumer Crash Recovery: Complete Guide

Imported reference note:

- this document was copied from the sibling `peegeeq` repository
- file names, classes, tests, and implementation-status statements below refer to that repository unless this repo adds matching code
- in `peegee-cache`, use this as recovery-design reference material, not as proof that the described implementation already exists here

## Executive Summary

**Problem:** If the outbox consumer crashes after polling but before completing message processing, messages remain stuck in `PROCESSING` state with `processed_at` set.

**Status:** Imported sibling-repo reference describing an implemented recovery design.

**Current Solution:** Timeout-based recovery (default: 5 minutes)

**Faster Alternatives:** Available with different trade-offs

---

## Part 1: Problem Analysis & Current Solution

### The Vulnerable Code Path

**File:** `OutboxConsumer.java` (lines 258-269)

```java
String sql = """
    UPDATE outbox
    SET status = 'PROCESSING', processed_at = $1
    WHERE id IN (
        SELECT id FROM outbox
        WHERE topic = $2 AND status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT $3
        FOR UPDATE SKIP LOCKED
    )
    RETURNING id, payload, headers, correlation_id, message_group, created_at
    """;
```

### What Happens During a Crash

1. Consumer polls messages and updates them to `PROCESSING` status
2. Sets `processed_at` timestamp to current time
3. Returns messages for processing
4. **[CRASH OCCURS HERE]** - Consumer process dies
5. Messages remain in `PROCESSING` state with `processed_at` set
6. Messages are never moved to `COMPLETED` status

### The Inconsistent State Created

| Field | Value | Problem |
|-------|-------|---------|
| Status | `PROCESSING` | Stuck indefinitely |
| processed_at | Set (not null) | Indicates processing started |
| retry_count | 0 | Never incremented |
| Result | Message orphaned | Will never be retried or completed |

---

## Part 2: Current Solution - Stuck Message Recovery

### Recovery Manager Implementation

**File:** `peegeeq-db/src/main/java/dev/mars/peegeeq/db/recovery/StuckMessageRecoveryManager.java`

In the sibling PeeGeeQ codebase, the system includes a `StuckMessageRecoveryManager` that:

1. **Detects stuck messages** - Identifies messages in `PROCESSING` state where `processed_at` is older than a configurable timeout (default: 5 minutes)

2. **Recovers automatically** - Resets stuck messages back to `PENDING` status, clearing `processed_at` so they can be reprocessed

3. **Configurable timeout** - Can be tuned based on expected message processing time

4. **Can be disabled** - For systems that want to handle recovery differently

### How It Works

```
PROCESSING message with processed_at > timeout
    ↓
Recovery manager detects it
    ↓
Resets status to PENDING
    ↓
Clears processed_at timestamp
    ↓
Message is repolled and reprocessed
```

### Test Coverage

**OutboxConsumerCrashRecoveryTest.java** (319 lines)
- Tests the exact crash scenario
- Verifies messages can be stuck in `PROCESSING` state
- Confirms recovery mechanism handles it

**StuckMessageRecoveryIntegrationTest.java** (486 lines)
- Comprehensive integration tests
- Tests with simulated consumer crashes
- Tests with disabled recovery
- Tests with thread crashes
- Verifies recovery statistics

### Configuration

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("prod");

// Default: 5 minutes
config.getQueueConfig().setRecoveryTimeout(Duration.ofMinutes(5));

// Default: 1 minute
config.getQueueConfig().setRecoveryCheckInterval(Duration.ofMinutes(1));

// Enable/disable recovery
config.getQueueConfig().setRecoveryEnabled(true);
```

---

## Part 3: Why 5-Minute Timeout?

### The Architectural Challenge

```
Poll (PENDING → PROCESSING)
    ↓
Process (async in thread pool)
    ↓ [CRASH HERE]
Complete (PROCESSING → COMPLETED)
```

### Why Not Real-Time Detection?

1. **Asynchronous Processing**
   - Handler runs in separate thread pool
   - System can't distinguish between crash and slow processing
   - Can't know if thread is alive or just slow

2. **Process Isolation**
   - Consumer could crash completely
   - No way to notify database immediately
   - Requires external monitoring

3. **Duplicate Prevention**
   - Conservative approach is safer
   - Better to wait than reprocess too early
   - Duplicates are worse than delays in financial systems

### Why This Design is Pragmatic

1. **Simplicity** - No complex coordination needed
2. **Reliability** - Works even if consumer crashes completely
3. **Correctness** - Avoids duplicate processing
4. **Configurability** - Timeout can be adjusted per deployment
5. **Proven Pattern** - Standard in RabbitMQ, Kafka, etc.

---

## Part 4: Real-Time Recovery Alternatives

### Option 1: Heartbeat-Based Recovery ⭐ RECOMMENDED

**How it works:**
```
Processing thread sends heartbeats every N seconds
Recovery manager monitors heartbeats
Missing heartbeat → message reset to PENDING
```

**Pros:**
- ✅ Detects crashes in seconds (not minutes)
- ✅ Works with async processing
- ✅ Minimal code changes
- ✅ Can be added to existing system

**Cons:**
- ❌ Adds database writes during processing
- ❌ Requires schema change (add `last_heartbeat` column)
- ❌ Slight performance overhead

**Recovery Time:** 5-10 seconds (configurable)

### Option 2: Consumer Lease Pattern

**How it works:**
```
Consumer acquires lease on message (with TTL)
Lease must be renewed during processing
Expired lease → message available for reprocessing
```

**Pros:**
- ✅ Automatic recovery without polling
- ✅ Prevents duplicate processing
- ✅ Scales well with multiple consumers

**Cons:**
- ❌ Requires consumer group coordination
- ❌ Complex implementation
- ❌ Needs distributed lock mechanism

**Recovery Time:** Lease TTL (10-30 seconds)

### Option 3: Synchronous Processing with Transactions

**How it works:**
```
Keep message in PENDING
Process within database transaction
Only update to COMPLETED after success
If crash: transaction rolls back, message stays PENDING
```

**Pros:**
- ✅ Guaranteed atomicity
- ✅ No stuck state possible
- ✅ Simplest semantics

**Cons:**
- ❌ Breaks async architecture
- ❌ Reduces throughput significantly
- ❌ Not suitable for long-running handlers

**Recovery Time:** Immediate (on next poll)

### Option 4: External Process Monitor

**How it works:**
```
Separate monitoring service watches consumer process
Detects process crash via health checks
Resets PROCESSING messages for dead consumer
```

**Pros:**
- ✅ Works with existing code
- ✅ Can monitor multiple consumers
- ✅ Flexible recovery logic

**Cons:**
- ❌ Requires separate service
- ❌ Complex distributed coordination
- ❌ Risk of false positives

**Recovery Time:** 10-30 seconds

---

## Part 5: Recommendations & Implementation

### For Most Use Cases: Reduce Timeout (EASIEST)

```java
PeeGeeQConfiguration config = new PeeGeeQConfiguration("prod");
config.getQueueConfig().setRecoveryTimeout(Duration.ofSeconds(30));
config.getQueueConfig().setRecoveryCheckInterval(Duration.ofSeconds(5));
```

**Result:** Recovery in 5-35 seconds instead of 5 minutes
**Effort:** Minimal (1 line of code)

### For Critical Systems: Add Heartbeat (RECOMMENDED)

1. Add `last_heartbeat` column to outbox table
2. Update heartbeat during processing
3. Recovery manager checks for stale heartbeats
4. Recovery time: 5-10 seconds
**Effort:** Medium (1-2 days)

### For Ultra-Low-Latency: Synchronous Processing

```java
// Process synchronously within transaction
messageHandler.handle(message).get(5, TimeUnit.SECONDS);
// Automatic rollback on crash
```

**Effort:** High (architectural change)

### Implementation Priority

1. **Immediate** (no code changes):
   - Reduce recovery timeout to 30 seconds
   - Increase recovery check frequency to 5 seconds

2. **Short-term** (1-2 days):
   - Implement heartbeat-based recovery
   - Add monitoring/alerting for stuck messages

3. **Long-term** (if needed):
   - Implement consumer group coordination
   - Add distributed lease mechanism

---

## Conclusion

The 5-minute timeout is **not a bug**, it's a **design choice** for reliability and simplicity. The system is **production-ready** with automatic recovery ensuring no messages are permanently lost.

For faster recovery without major architectural changes, reduce the timeout to 30 seconds or implement heartbeat-based monitoring.



---

## Source: docs/guidelines/MAVEN_TOOLCHAINS_EXPLAINED.md


# Maven Toolchains - How It Works

## Overview

Maven Toolchains allows you to **run Maven with one JDK** (e.g., Java 24) while **compiling and testing with a different JDK** (e.g., Java 21).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Maven JVM (Java 24)                                         │
│ ├── Reads pom.xml                                           │
│ ├── Resolves dependencies                                   │
│ ├── Orchestrates build lifecycle                            │
│ └── Spawns forked processes ──────────────────┐             │
└────────────────────────────────────────────────│─────────────┘
                                                 │
                                                 ▼
┌─────────────────────────────────────────────────────────────┐
│ Forked JVM (Java 21) - Compiler                             │
│ ├── Executable: C:\Path\To\JDKs\jdk-21\bin\javac            │
│ ├── Compiles source code                                    │
│ └── Produces Java 21 bytecode (class version 65)            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Forked JVM (Java 21) - Test Runner                          │
│ ├── Executable: C:\Path\To\JDKs\jdk-21\bin\java            │
│ ├── Runs JUnit tests                                        │
│ ├── JaCoCo agent instruments bytecode                       │
│ └── No Java 24 compatibility issues!                        │
└─────────────────────────────────────────────────────────────┘
```

## Configuration Files

### 1. `~/.m2/toolchains.xml` - JDK Registry

```xml
<toolchains>
    <toolchain>
        <type>jdk</type>
        <provides>
            <version>21</version>
        </provides>
        <configuration>
            <jdkHome>C:\Path\To\JDKs\jdk-21</jdkHome>
        </configuration>
    </toolchain>
</toolchains>
```

**Purpose**: Tells Maven where to find different JDK versions on your system.

### 2. `pom.xml` - Toolchain Request

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-toolchains-plugin</artifactId>
    <configuration>
        <toolchains>
            <jdk>
                <version>21</version>  <!-- Request Java 21 -->
            </jdk>
        </toolchains>
    </configuration>
</plugin>
```

**Purpose**: Declares which JDK version the project requires.

## How Forking Works

### Compiler Plugin (maven-compiler-plugin)

When Maven compiles code:

1. **Reads toolchain**: Gets Java 21 path from build context
2. **Forks process**: Launches `C:\Path\To\JDKs\jdk-21\bin\javac`
3. **Passes arguments**: Source files, classpath, `--release 21`
4. **Waits for completion**: Collects compilation results
5. **Returns to Maven**: Maven continues with next phase

**Evidence from logs**:
```
[INFO] Toolchain in maven-compiler-plugin: JDK[C:\Path\To\JDKs\jdk-21]
[INFO] Compiling 45 source files with javac [forked debug release 21]
```

### Surefire Plugin (maven-surefire-plugin)

When Maven runs tests:

1. **Creates temporary JAR**: `surefirebooter-*.jar` with test classpath
2. **Builds command line**: 
   ```
    C:\Path\To\JDKs\jdk-21\bin\java.exe
   -javaagent:jacoco-agent.jar
   -XX:+EnableDynamicAgentLoading
   -jar surefirebooter-*.jar
   ```
3. **Forks process**: Launches new JVM with Java 21
4. **Runs tests**: JUnit executes in forked JVM
5. **Collects results**: Maven receives test results via IPC

**Evidence from logs**:
```
[INFO] Toolchain in maven-surefire-plugin: JDK[C:\Path\To\JDKs\jdk-21]
[DEBUG] Forking command line: ... -jar surefirebooter-*.jar
```

## Why This Solves the JaCoCo Problem

### Before (Java 24):
```
Maven (Java 24) → Tests run in Java 24 → JaCoCo tries to instrument Java 24 bytecode
                                       → IllegalClassFormatException (unsupported version 68)
```

### After (Java 21 via Toolchains):
```
Maven (Java 24) → Forks Java 21 → Tests run in Java 21 → JaCoCo instruments Java 21 bytecode
                                                        → ✅ Success! (version 65 supported)
```

## Key Benefits

1. **Flexibility**: Use latest Maven features (requires newer Java) while targeting older Java versions
2. **Consistency**: Ensure all developers use the same JDK version for builds
3. **CI/CD**: Build server can have multiple JDKs, Maven picks the right one
4. **Tool Compatibility**: Avoid issues like JaCoCo not supporting bleeding-edge Java versions

## Verification

Check which JDK is being used:

```bash
# Maven's JDK
mvn -version
# Output: Java version: 24

# Compilation JDK
mvn compile -X | Select-String "Toolchain in maven-compiler-plugin"
# Output: Toolchain in maven-compiler-plugin: JDK[C:\Path\To\JDKs\jdk-21]

# Test JDK
mvn test -X | Select-String "Toolchain in maven-surefire-plugin"
# Output: Toolchain in maven-surefire-plugin: JDK[C:\Path\To\JDKs\jdk-21]
```

## References

- [Maven Toolchains Documentation](https://maven.apache.org/guides/mini/guide-using-toolchains.html)
- [Maven Compiler Plugin - Using Different JDK](https://maven.apache.org/plugins/maven-compiler-plugin/examples/compile-using-different-jdk.html)
- [Maven Surefire Plugin - Forking](https://maven.apache.org/surefire/maven-surefire-plugin/examples/fork-options-and-parallel-execution.html)


