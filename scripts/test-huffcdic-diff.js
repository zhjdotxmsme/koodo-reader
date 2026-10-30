const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const root = path.resolve(__dirname, "..");
const android = path.join(root, "android");
const fixturePath = path.join(android, "engine/mobi/build/huffcdic-diff-fixture.bin");
const mobiPath = path.join(android, "app/src/main/assets/foliate/mobi.js");

function runFixtureProducer() {
  const windows = process.platform === "win32";
  const result = spawnSync(
    windows ? "gradlew.bat" : "./gradlew",
    [
      ":engine:mobi:test",
      "--tests",
      "com.koodoreader.engine.mobi.HuffCdicDifferentialFixtureTest",
      "--rerun-tasks",
    ],
    { cwd: android, stdio: "inherit", shell: windows },
  );
  if (result.error) throw result.error;
  assert.equal(result.status, 0, "Kotlin HUFF/CDIC fixture producer failed");
}

function readFixture() {
  const bytes = fs.readFileSync(fixturePath);
  let offset = 0;
  const take = length => {
    assert.ok(offset + length <= bytes.length, "truncated differential fixture");
    const value = bytes.subarray(offset, offset + length);
    offset += length;
    return value;
  };
  const uint32 = () => take(4).readUInt32BE();
  const blob = () => take(uint32());

  assert.equal(take(8).toString("ascii"), "HCDIF01\0");
  const cases = Array.from({ length: uint32() }, () => {
    const name = blob().toString("utf8");
    const records = Array.from({ length: uint32() }, blob);
    const input = blob();
    const kotlin = blob();
    return { name, records, input, kotlin };
  });
  assert.equal(offset, bytes.length, "unexpected trailing differential fixture bytes");
  return cases;
}

async function loadFoliateHuffcdic() {
  const source = fs.readFileSync(mobiPath, "utf8") + "\nexport { huffcdic };\n";
  const url = `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
  return (await import(url)).huffcdic;
}

async function main() {
  runFixtureProducer();
  const cases = readFixture();
  assert.deepEqual(
    cases.map(testCase => testCase.name),
    ["direct-table1", "table2-codeLength32", "nested-compressed-slice"],
  );
  const huffcdic = await loadFoliateHuffcdic();

  for (const testCase of cases) {
    const records = new Map(testCase.records.map((bytes, index) => [index, bytes]));
    const decompress = await huffcdic(
      { huffcdic: 0, numHuffcdic: records.size },
      async index => {
        const bytes = records.get(index);
        assert.ok(bytes, `foliate requested unexpected record ${index}`);
        return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      },
    );
    const foliate = Buffer.from(decompress(new Uint8Array(testCase.input)));
    assert.deepEqual(foliate, testCase.kotlin, `${testCase.name} output bytes differ`);
  }
  console.log("huffcdic diff OK");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
