# Project Zomboid Map Decompiler
This tool is made by AI Sonnet and is capable of decompiling project zomboid map back to zombiespawnmap, pzw, tmx and tbx formats by reading `.lotheader` and `.lotpack`. It currently only works on B41 where most of the old maps are.

Do note that this is a reverse engineer aka decompiling of map, it is not an 1:1 to the map source and will never be.

The command to run this is simply
```
java decompile-pzmap.java --tilesdir path/to/tilesheets/dir --mapdir path/to/map/dir --rule path/to/rules/file --tilezedconfig path/to/tilezed/config/folder --output path/to/output/dir
```

The tilesheets directory is very important because PZ world editor build its own indexes using the tilesheet directory.
These indexes makes all the floors, trees etc you see on the world editor. So, if the indexes are wrong, what you see will be wrong.

We use the `Rules.txt` file as data source to categorize what tiles to be placed at `0_Floor` and `0_Vegetation`.

We use the files in TileZed config folder as data source to categorize what tiles to be placed at which layer for `tbx`. Unrecognized tiles hopefully should be added to `Unidentified` layer.

If you are decompiling a map that uses mod tiles, you need to extract the mod tilesheets to the tilesheets folder. If the said map also used custom `Rules.txt` which involve using additional tiles for `0_Floor` and `0_Vegetation`, you will have to update the `Rules.txt` for the decompiler to put them in correct layers. Likewise, if it uses mod tiles for `tbx`, you too have to add them into `TileZed` and run the decompiler again to put them in correct layers.