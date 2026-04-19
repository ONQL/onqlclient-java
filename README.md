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

Add the JitPack repository, then depend on a tag, branch, or commit:

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.ONQL</groupId>
  <artifactId>onqlclient-java</artifactId>
  <version>v1.0.0</version>   <!-- or: main-SNAPSHOT -->
</dependency>
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

        client.insert("mydb.users", "{\"id\":\"u1\",\"name\":\"John\",\"age\":30}");

        String rows = client.onql("select * from mydb.users where age > 18");
        System.out.println(rows);

        client.update("mydb.users.u1", "{\"age\":31}");
        client.delete("mydb.users.u1");

        client.close();
    }
}
```

## API Reference

### `ONQLClient.create(host, port)`

Creates and returns a connected client instance.

### `client.sendRequest(keyword, payload)`

Sends a raw request frame and waits for a response. Returns the response
payload as a `String`.

### `client.close()`

Closes the connection and releases resources.

## Direct ORM-style API

On top of raw `sendRequest`, the client exposes convenience methods for the
`insert` / `update` / `delete` / `onql` operations. Each one builds the
standard payload envelope for you and parses the `{error, data}` envelope
from the server response — throwing `RuntimeException` on a non-empty `error`,
returning the raw `data` substring on success.

Because the driver is dependency-free, every JSON-valued parameter is passed
as a **pre-serialized JSON string**. Use your favorite library (Jackson,
Gson, org.json, …) to serialize.

The `path` argument is a **dotted string** identifying what you're operating
on:

| Path shape | Meaning |
|------------|---------|
| `"mydb.users"` | Table (used by `insert`) |
| `"mydb.users.u1"` | Record id `u1` (used by `update` / `delete`) |

### `client.insert(path, recordJson)`

Insert a **single** record.

```java
client.insert("mydb.users", "{\"id\":\"u1\",\"name\":\"John\",\"age\":30}");
```

### `client.update(path, recordJson)` / `client.update(path, recordJson, protopass)`

Update the record at `path`.

```java
client.update("mydb.users.u1", "{\"age\":31}");
client.update("mydb.users.u1", "{\"active\":false}", "admin");
```

### `client.delete(path)` / `client.delete(path, protopass)`

Delete the record at `path`.

```java
client.delete("mydb.users.u1");
```

### `client.onql(query)` / `client.onql(query, protopass, ctxkey, ctxvaluesJson)`

Run a raw ONQL query. Returns the raw `data` substring of the server envelope.

```java
String data = client.onql("select * from mydb.users where age > 18");
```

### `client.build(query, values...)`

Replace `$1`, `$2`, … placeholders with values. Strings are automatically
double-quoted; numbers and booleans are inlined verbatim.

```java
String q = client.build(
    "select * from mydb.users where name = $1 and age > $2",
    "John", 18);
// -> select * from mydb.users where name = "John" and age > 18
String data = client.onql(q);
```

### `ONQLClient.processResult(raw)`

Static helper that parses the `{error, data}` server envelope. Throws on
non-empty `error`; returns the raw `data` substring on success.

## Protocol

The client communicates over TCP using a delimiter-based message format:

```
<request_id>\x1E<keyword>\x1E<payload>\x04
```

- `\x1E` — field delimiter
- `\x04` — end-of-message marker

## License

MIT
