"""Package an explicit allowlist of JARs and examples; never runtime data."""
from pathlib import Path
import hashlib,json,shutil,subprocess,zipfile
root=Path(__file__).resolve().parent.parent
out=root/'build/release';out.mkdir(parents=True,exist_ok=True)
artifacts=[]
for rel in ['agent/build/libs/PlexonPanel-2.0.0.jar','host-agent/build/libs/plexonpanel-host-2.0.0.jar']:
 source=root/rel
 if not source.is_file():raise SystemExit('Build the JAR first: '+rel)
 dest=out/source.name;shutil.copyfile(source,dest);artifacts.append(dest)
archive=out/'PlexonPanel-2.0.0-examples.zip'
examples=[root/'agent/src/main/resources/config.yml',*sorted((root/'host-agent/examples').glob('*')),*sorted((root/'docs').glob('*.md')),root/'README.md',root/'CHANGELOG.md',root/'PRIVACY.md']
with zipfile.ZipFile(archive,'w') as z:
 for path in examples:
  info=zipfile.ZipInfo(path.relative_to(root).as_posix(),(1980,1,1,0,0,0));info.external_attr=0o100644<<16;info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,path.read_bytes())
artifacts.append(archive)
commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=root,text=True).strip())
manifest=out/'release-manifest.json';manifest.write_text(json.dumps({'version':'2.0.0','protocolVersion':3,'java':25,'sourceCommit':commit,'workingTreeModified':dirty,'productionAcceptance':'see docs/release-gates.json'},indent=2)+'\n');artifacts.append(manifest)
(out/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in artifacts))
print('Packaged both JARs, examples, manifest and SHA256SUMS.txt')
