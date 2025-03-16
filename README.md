# Datomic MCP Server

Built with [Modex](https://github.com/theronic/modex).

Will improve README soon.

Set environment variable DATOMIC_URI.

## Build Uberjar & Configure Claude Desktop

```json
{
  "mcpServers": {
    "modex-datomic-mcp": {
      "command": "java",
      "args": ["-jar", "/Users/petrus/code/datomic-mcp/target/theronic-datomic-mcp-0.1.0.jar"],
      "env": {"DATOMIC_URI": "<your datomic URI here>"}
    }
  },
  "globalShortcut": ""
}
```

## License 

In summary:
- **Free for non-commercial use**: Use it, modify it, share it under [GPLv3](https://www.gnu.org/licenses/gpl-3.0.html) at no cost, just keep it open source.
- **Commercial use**: Want to keep your changes private? Pay $20 once-off for a perpetual commercial license. This covers the cost of my AI tokens to keep building this in public.

This tool is licensed under the [GNU General Public License v3.0 (GPLv3)](https://www.gnu.org/licenses/gpl-3.0.html). You are free to use, modify, and distribute it, provided that any derivative works are also licensed under the GPLv3 and made open source. This ensures the tool remains freely available to the community while requiring transparency for any changes.

If you wish to use or modify this tool in a proprietary project—without releasing your changes under the GPLv3—you 
may purchase a commercial license. This allows you to keep your modifications private for personal or commercial use.
To obtain a commercial license, please contact me at [modex@petrus.co.za](mailto:modex@petrus.co.za).

## Author(s)

- [Petrus Theron](http://petrustheron.com)
