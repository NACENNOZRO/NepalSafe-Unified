#!/usr/bin/env python3
"""Make smaller upload archives without changing a module's source files or model/data assets."""
import argparse
import os
from pathlib import Path
import zipfile

GENERATED = {'.git', 'node_modules', '.gradle', '.expo', '.idea', '__pycache__'}

def pack(source, output):
    source = source.resolve()
    if not source.is_dir():
        raise ValueError(f'Not a module directory: {source}')
    output = output.resolve()
    if output == source or source in output.parents:
        raise ValueError('Output must be outside the module folder')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for directory, dirs, files in os.walk(source):
            folder = Path(directory)
            dirs[:] = [d for d in dirs if d not in GENERATED and not (folder/d/'pyvenv.cfg').is_file()
                       and not (d == 'build' and ((folder/'build.gradle').is_file() or (folder/'build.gradle.kts').is_file()))]
            for name in dirs:
                if (folder/name).is_symlink():
                    raise ValueError(f'Symlink requires manual review before packaging: {folder/name}')
            for name in files:
                path = folder / name
                if path.is_symlink():
                    raise ValueError(f'Symlink requires manual review before packaging: {path}')
                if name in {'.DS_Store', 'local.properties'} or name.endswith('.pyc'):
                    continue
                archive.write(path, Path(source.name) / path.relative_to(source))
    print(f'{output.name}: {output.stat().st_size / 1024 / 1024:.1f} MiB')

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('modules', nargs='+', type=Path)
    p.add_argument('--output', type=Path, default=Path.cwd() / 'source-uploads')
    args = p.parse_args()
    for module in args.modules:
        pack(module, args.output / (module.name + '-source.zip'))
