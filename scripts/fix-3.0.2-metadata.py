from pathlib import Path

p = Path("protocol/src/test/java/io/github/zpkdxgames/plexonpanel/protocol/ReleaseVersionConsistencyTest.java")
text = p.read_text().replace('private static final String EXPECTED_VERSION = "3.0.1";', 'private static final String EXPECTED_VERSION = "3.0.2";', 1)
p.write_text(text)

p = Path(".github/workflows/release.yml")
text = p.read_text().replace("3.0.1", "3.0.2")
p.write_text(text)
