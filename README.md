# Project Zomboid Map Decompiler
This tool is made by AI Sonnet and is capable of decompiling project zomboid map back to pzw, tmx and tbx formats, by reading `.lotheader` and `.lotpack`.
It is currently not able to rebuild the zombie heat map but I will get into it soon.
It currently only works on B41 where most of the old maps are.

The command to run this is simply
```
java decompile-pzmap.java --tilesdir path/to/tilesheets/dir --mapdir path/to/map/dir --output path/to/output/dir
```

The tilesheets directory is very important because PZ world editor build its own indexes using the tilesheet directory.
These indexes makes all the floors, trees etc you see on the world editor. So, if the indexes are wrong, what you see will be wrong.
If you are decompiling a map that uses mod tiles, you need to extract the mod tilesheets to the tilesheets folder.
