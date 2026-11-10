import importlib.util
from pathlib import Path
import io,tarfile,tempfile,unittest
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('recovery',ROOT/'scripts/recovery.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
class RecoverySafetyTests(unittest.TestCase):
 def test_image_export_requires_all_layers_and_running_images(self):
  import json
  with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as work:
   archive=Path(work)/'images.tar'
   for missing in (False,True):
    with tarfile.open(archive,'w') as target:
     entries={'manifest.json':json.dumps([{'Config':'abc.json','Layers':['layer.tar']}]).encode(),'abc.json':b'{}'}
     if not missing:entries['layer.tar']=b'layer fixture'
     for name,data in entries.items():
      item=tarfile.TarInfo(name);item.size=len(data);target.addfile(item,io.BytesIO(data))
    if missing:
     with self.assertRaises(ValueError):r.verify_image_export(archive,{'api':'sha256:abc'})
    else:
     r.verify_image_export(archive,{'api':'sha256:abc'})
     with self.assertRaises(ValueError):r.verify_image_export(archive,{'api':'sha256:missing'})
 def test_archive_cannot_escape_or_restore_links(self):
  (ROOT/'tmp').mkdir(exist_ok=True)
  with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as work:
   work=Path(work)
   for name,link in [('../outside',False),('/absolute',False),('private/link',True)]:
    archive=work/'bad.tar'
    with tarfile.open(archive,'w') as f:
     item=tarfile.TarInfo(name);item.size=0
     if link:item.type=tarfile.SYMTYPE;item.linkname='/etc/passwd'
     f.addfile(item,io.BytesIO())
    with self.assertRaises(ValueError):r.extract(archive,work/'target')
 def test_backup_target_cannot_replace_parent_or_existing_directory(self):
  with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as work:
   work=Path(work)
   with self.assertRaises(ValueError):r.private_directory(work,work)
   child=r.private_directory(work/'new',work)
   with self.assertRaises(FileExistsError):r.private_directory(child,work)
   with self.assertRaises(ValueError):r.private_directory(work.parent/'outside',work)

 def test_maintenance_cannot_send_credentials_off_host(self):
  from unittest.mock import patch
  with patch.object(r,'run',return_value=b'127.0.0.1 192.0.2.10'):
   r.maintenance_url('https://192.0.2.10')
   r.maintenance_url('http://127.0.0.1:8080')
   for url in ('https://192.0.2.20','http://192.0.2.10','https://' + ':'.join(['user','password']) + '@192.0.2.10','https://192.0.2.10/path','file:///tmp/data'):
    with self.assertRaises(ValueError):r.maintenance_url(url)
 def test_corrupted_bundle_fails_before_creating_target(self):
  import argparse,json
  from unittest.mock import patch
  with tempfile.TemporaryDirectory(dir=ROOT/'tmp') as work:
   work=Path(work);backup=work/'backups/bundle';backup.mkdir(parents=True)
   names=('database.dump','configuration.enc','source.tar.gz','images.tar.gz')
   for name in names:(backup/name).write_bytes(b'corrupted fixture')
   (backup/'manifest.json').write_text(json.dumps({'format':1,'files':{name:'0'*64 for name in names}}))
   with patch.object(r,'ROOT',work):
    with self.assertRaises(ValueError):r.restore(argparse.Namespace(directory=backup,target=work/'tmp/fresh',key_file=work/'key',port=8089))
   self.assertFalse((work/'tmp/fresh').exists())
