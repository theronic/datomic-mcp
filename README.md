# Datomic MCP Server

Built with [Modex](https://github.com/theronic/modex).

Set environment variable `DATOMIC_URI` in your MCP config. Example below.

## Build Uberjar & Configure Claude Desktop

```json
{
  "mcpServers": {
    "modex-datomic-mcp": {
      "command": "java",
      "args": ["-jar", "/Users/petrus/code/datomic-mcp/target/theronic-datomic-mcp-0.3.0.jar"],
      "env": {"DATOMIC_URI": "<your datomic URI here>"}
    }
  },
  "globalShortcut": ""
}
```

## Datomic API Support

- [x] Concurrent queries (async message handling since Modex 0.3.0)
- [x] `datomic.api/q`
- [x] `datomic.api/datoms`
- [x] `datomic.api/with` (via `q-with` tool)
- [x] `datomic.api/pull`
- [x] `datomic.api/pull-many`
- [x] `datomic.api/entity`
- [x] `datomic.api/touch`
- [x] `datomic.api/entid`
- [ ] `datomic.api/transact` – not sure if good idea :)
- [ ] Send Progress Messages Connection Progress
- [ ] Better cursor-based pagination
- [ ] Stable `db` basis (currently each query runs `(d/db conn)`) – easy to fix.
- [ ] `d/as-of` support. Related to basis above.

## License

MIT Licence. Free for commercial & non-commercial use.

All I ask is that if you find a bug in datomic-mcp or Modex, please report it :)

Note that Modex itself has a different licence.

## Author(s)

- [Petrus Theron](http://petrustheron.com)
