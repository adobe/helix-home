# JSON Handling in Helix 3

JSONs are requested from the helix pipeline-service with optional filter query parameters:

| name | description | default |
|-|-|-|
| `limit` | Limits the size of the returned data array | _max integer_ |
| `offset` | Offsets the slice of the returned data array | `0` |
| `sheet` | Name(s) of workbook sheets to return. The sheet must be prefixed with `helix-`. Can be multiple. | `sheet=cars&sheet=trains` |

The pipeline assumes that the complete workbook is stored in the content-bus, or code-bus (but can also deal with single sheet data for backward compatibility purposes).
If no request parameters are given, it can also serve non-workbook serializations and broken JSONs.

## Workbook storage

The workbooks are fetched, processed and stored in the content-bus during the _update preview_ action. The stored content is always a multi-sheet representation of the sheets in the workbook. Since only the `helix-` prefixed sheets or the first of non-helix sheets can be surfed, those are already pre-filtered:

- if the workbook contains no `helix-` prefixed sheets, use the first sheet and name it `default`
- otherwise, use all `helix-` prefixed sheets
- store the sheets as `multisheet` json representation (see below)

## Delivery

### Data loading

- Load the data from the content-bus, if missing, load from code-bus
- If no data can be loaded, return 404
- Check if the data is valid JSON and if it is a multi-sheet. If not, return the data as-is, if, and only if no request parameters are provided.
- If the data contains only a single sheet, name it `helix-default` and construct multi-sheet data (backward compat)

### Sheet selection

- if the `sheet` parameter contains one or several sheet names, select the ones from the stored workbook.
- otherwise without a `sheet` parameter, if there is a `helix-default` sheet, select this one
- otherwise select all.

Examples:
| sheets in workbook | requested | selected | reason |
|-|-|-|-|
| `['Sheet1', 'Sheet2']` | `[]` | `Sheet1` | no helix sheet, none requested, selects first one |
| `['Sheet1', 'Sheet2']` | `['Sheet1']` | `[]` | `Sheet1` is not prefixed with `helix-` |
| `['Sheet1', 'Sheet2', 'helix-default`]` | `[]` | `helix-default` | none requested select `helix-default` |
| `['Sheet1', 'helix-cars', 'helix-trains']` | `[]` | `['helix-cars', 'helix-trains']` | since there are helix sheets but no default |
| `['Sheet1', 'helix-cars', 'helix-trains']` | `['cars']` | `['helix-cars']` | selects the correct helix sheets |

### Limit and offset

The `limit` and `offset` slice the data array of the selected sheets by returning the array entries starting a `offset` and ending at `offset + limit` exclusive.

The slices are equal for all sheets and currently there is no way to specify different limits and offsets for multi-sheet responses.

### Response

**no sheet**

If no sheet is selected, a `404` is returned.

**single sheet**

If only 1 sheet is selected, the response contains only the sheet data.

example:
```
{
  ":type": "sheet",
  ":version": 3,
  "data": [
    {
      "brand": "Audit",
      "model": "TT"
    },
    {
      "brand": "Tesla",
      "model": "S"
    }
  ],
  "offset": 0,
  "limit": 2,
  "total": 2
}
```

the `total` property contains the total number of rows in the sheet, the `limit` the number of rows returned.


**multi sheet**

In case multiple sheets are selected, the response contains all of them.
example:

```
{
  ":names": [
    "cars",
    "trains"
  ],
  ":type": "multi-sheet",
  ":version": 3,
  "cars":{
    "data": [
      {
        "brand": "Audit",
        "model": "TT"
      },
      {
        "brand": "Tesla",
        "model": "S"
      }
    ],
    "offset": 0,
    "limit": 2,
    "total": 2
  }, 
  "trains":{
    "data": [
      {
        "name": "TGV",
        "max speed": "320"
      },
      {
        "name": "ICE",
        "max speed": "350"
      },
      {
        "name": "Shinkansen",
        "max speed": "443"
      }
    ],
    "offset": 0,
    "limit": 3,
    "total": 3
  } 
}
```






