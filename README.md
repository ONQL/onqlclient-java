# ONQL Java Driver

Official Java client for the ONQL database server.

## Installation

### Maven (Maven Central)

```xml
<dependency>
  <groupId>org.onql</groupId>
  <artifactId>onql-client</artifactId>
  <version>1.0.0</version>
</dependency>
```

### Gradle (Maven Central)

```groovy
implementation 'org.onql:onql-client:1.0.0'
```

### JitPack (build straight from GitHub)

Add the JitPack repository:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
```

Then depend on a tag, branch, or commit:

```xml
<dependency>
  <groupId>com.github.ONQL</groupId>
  <artifactId>onqlclient-java</artifactId>
  <version>v1.0.0</version>   <!-- or: main-SNAPSHOT -->
</dependency>
```

Gradle equivalent:

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies {
    implementation 'com.github.ONQL:onqlclient-java:v1.0.0'
}
```

### Build from source

```bash
git clone https://github.com/ONQL/onqlclient-java.git
cd onqlclient-java
mvn clean install
```

## Quick Start

```java
import org.onql.ONQLClient;

public class Main {
    public static void main(String[] args) throws Exception {
        ONQLClient client = ONQLClient.create("localhost", 5656);

        // Execute a query
        String result = client.sendRequest("onql",
            "{\"db\":\"mydb\",\"table\":\"users\",\"query\":\"name = \\\"John\\\"\"}");
        System.out.println(result);

        // Subscribe to live updates
        String rid = client.subscribe("", "name = \"John\"", (id, keyword, payload) -> {
            System.out.println("Update: " + payload);
        });

        // Unsubscribe
        client.unsubscribe(rid);

        client.close();
    }
}
```

## API Reference

### `ONQLClient.create(host, port)`

Creates and returns a connected client instance.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `host` | `String` | `"localhost"` | Server hostname |
| `port` | `int` | `5656` | Server port |

### `client.sendRequest(keyword, payload)`

Sends a request and waits for a response. Returns the response payload as a `String`.

### `client.subscribe(onquery, query, callback)`

Opens a streaming subscription. Returns the subscription ID.

### `client.unsubscribe(rid)`

Stops receiving events for a subscription.

### `client.close()`

Closes the connection and releases resources.

## Direct ORM-style API

On top of raw `sendRequest`, the client exposes convenience methods for the
common `insert` / `update` / `delete` / `onql` operations. These methods build
the standard payload envelope for you and parse the `{error, data}` envelope
from the server response — throwing a `RuntimeException` on error and
returning the raw `data` substring on success.

Because the driver is dependency-free, JSON for complex parameters (records,
query, ids) is passed as a **pre-serialized JSON string**. Use your favorite
library (Jackson, Gson, org.json, ...) to serialize and parse — the driver
stays out of the way.

Call `client.setup(db)` once to bind a default database name; every subsequent
`insert` / `update` / `delete` / `onql` call will use it.

### `client.setup(db)`

Sets the default database name. Returns `this`, so calls can be chained.

```java
client.setup("mydb");
```

### `client.insert(table, recordsJson)`

Insert one record or an array of records.

| Parameter | Type | Description |
|-----------|------|-------------|
| `table` | `String` | Target table |
| `recordsJson` | `String` | A JSON object, or a JSON array of objects |

Returns the raw `data` substring of the server envelope. Throws a
`RuntimeException` if the server returned a non-empty `error` field.

```java
client.insert("users", "{\"name\":\"John\",\"age\":30}");
client.insert("users", "[{\"name\":\"A\"},{\"name\":\"B\"}]");
```

### `client.update(table, recordsJson, queryJson)` / `client.update(table, recordsJson, queryJson, protopass, idsJson)`

Update records matching `queryJson`. Both parameters are pre-serialized JSON.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `table` | `String` | — | Target table |
| `recordsJson` | `String` | — | JSON object of fields to update |
| `queryJson` | `String` | — | JSON query |
| `protopass` | `String` | `"default"` | Proto-pass profile |
| `idsJson` | `String` | `"[]"` | JSON array of explicit record IDs |

```java
client.update("users", "{\"age\":31}", "{\"name\":\"John\"}");
client.update("users", "{\"active\":false}", "{\"id\":\"u1\"}", "admin", "[]");
```

### `client.delete(table, queryJson)` / `client.delete(table, queryJson, protopass, idsJson)`

Delete records matching `queryJson`. Same parameter semantics as `update`.

```java
client.delete("users", "{\"active\":false}");
```

### `client.onql(query)` / `client.onql(query, protopass, ctxkey, ctxvaluesJson)`

Run a raw ONQL query. Returns the raw `data` substring of the server envelope.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | `String` | — | ONQL query text |
| `protopass` | `String` | `"default"` | Proto-pass profile |
| `ctxkey` | `String` | `""` | Context key |
| `ctxvaluesJson` | `String` | `"[]"` | JSON array of context values |

```java
String data = client.onql("select * from users where age > 18");
```

### `client.build(query, values...)`

Replace `$1`, `$2`, … placeholders with values. Strings are automatically
double-quoted; numbers and booleans are inlined verbatim.

```java
String q = client.build("select * from users where name = $1 and age > $2", "John", 18);
// -> select * from users where name = "John" and age > 18
String data = client.onql(q);
```

### `ONQLClient.processResult(raw)`

Static helper that parses the standard `{error, data}` server envelope.
Throws on non-empty `error`; returns the raw `data` substring on success.
Exposed so callers can reuse it with `sendRequest` payloads if they prefer to
build payloads themselves.

### Full example

```java
import org.onql.ONQLClient;

public class Main {
    public static void main(String[] args) throws Exception {
        ONQLClient client = ONQLClient.create("localhost", 5656);
        client.setup("mydb");

        client.insert("users", "{\"name\":\"John\",\"age\":30}");

        String rows = client.onql(
            client.build("select * from users where age >= $1", 18)
        );
        System.out.println(rows);

        client.update("users", "{\"age\":31}", "{\"name\":\"John\"}");
        client.delete("users", "{\"name\":\"John\"}");

        client.close();
    }
}
```

## Protocol

The client communicates over TCP using a delimiter-based message format:

```
<request_id>\x1E<keyword>\x1E<payload>\x04
```

- `\x1E` — field delimiter
- `\x04` — end-of-message marker

## License

MIT
