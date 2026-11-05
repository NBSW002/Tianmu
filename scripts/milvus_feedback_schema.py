"""Print a Milvus REST v2 collection-create payload. Offline only; never sends requests."""
import argparse
import json
import re


def identifier(value):
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", value):
        raise argparse.ArgumentTypeError("Expected a Milvus identifier (1..128 characters)")
    return value


def dimension(value):
    number = int(value)
    if not 1 <= number <= 32768:
        raise argparse.ArgumentTypeError("Dimension must be 1..32768")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--collection", required=True, type=identifier)
    parser.add_argument("--database", default="default", type=identifier)
    parser.add_argument("--dimension", required=True, type=dimension)
    args = parser.parse_args()
    fields = [{"fieldName": name, "dataType": "VarChar",
               "elementTypeParams": {"max_length": str(length)}}
              for name, length in [("id", 64), ("scope_key", 64), ("record_id", 21), ("content_version", 64)]]
    fields[0]["isPrimary"] = True
    fields.append({"fieldName": "vector", "dataType": "FloatVector",
                   "elementTypeParams": {"dim": str(args.dimension)}})
    print(json.dumps({
        "dbName": args.database, "collectionName": args.collection,
        "schema": {"autoId": False, "enableDynamicField": False, "fields": fields},
        "indexParams": [{"fieldName": "vector", "indexName": "vector_cosine",
                         "metricType": "COSINE", "params": {"index_type": "AUTOINDEX"}}],
        "params": {"consistencyLevel": "Strong"},
    }, indent=2))


if __name__ == "__main__":
    main()
