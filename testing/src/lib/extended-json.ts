export function dateValue(value: Date): { $date: string } {
  return { $date: value.toISOString() };
}

export function longValue(value: number | bigint): { $numberLong: string } {
  return { $numberLong: value.toString() };
}

export function objectIdValue(hex: string): { $oid: string } {
  return { $oid: hex };
}

export function binaryValue(bytes: Buffer, subType: string): { $binary: { base64: string; subType: string } } {
  return { $binary: { base64: bytes.toString("base64"), subType } };
}

export function javaLegacyUuid(uuid: string): { $binary: { base64: string; subType: string } } {
  const bytes = Buffer.from(uuid.replace(/-/g, ""), "hex");
  bytes.subarray(0, 8).reverse();
  bytes.subarray(8, 16).reverse();
  return binaryValue(bytes, "03");
}

export function everyTypeValue(at: Date): Record<string, unknown> {
  return {
    int32: 5,
    int64: longValue(9_007_199_254_740_993n),
    double: 1.5,
    notANumber: { $numberDouble: "NaN" },
    decimal: { $numberDecimal: "1.25" },
    yes: true,
    nothing: null,
    objectId: objectIdValue("665f1c2e8b3e4a0012345678"),
    date: dateValue(at),
    earlyDate: { $date: longValue(-315_619_200_000) },
    binary: binaryValue(Buffer.from([0, 1, 2, 255]), "00"),
    uuid: binaryValue(Buffer.from("0b7e8a3c6c554b3a9d8a111111111111", "hex"), "04"),
    regex: { $regularExpression: { pattern: "^/login", options: "i" } },
    timestamp: { $timestamp: { t: 1, i: 2 } },
    minKey: { $minKey: 1 },
    maxKey: { $maxKey: 1 },
    undefinedValue: { $undefined: true },
    symbol: { $symbol: "symbol" },
    code: { $code: "return 1;" },
    codeWithScope: { $code: "return x;", $scope: { x: 1 } },
    nested: { list: [1, "two", { three: 3 }] },
  };
}
