///usr/bin/env java "$0" "$@"; exit $?
/**
 * PZ Lot → TMX Reverser
 * =====================
 * Reconstructs a TileZed / WorldEd-compatible .tmx from compiled PZ map files.
 *
 * ── Input ────────────────────────────────────────────────────────────────────
 *   X_Y.lotheader          tile name list, rooms, buildings, zombie-spawn grid
 *   world_X_Y.lotpack      RLE tile data (30×30 chunks/cell, 10×10 tiles/chunk)
 *   <tilesdir>/            the Tiles folder from the PZ modding tools, e.g.
 *                          Steam/…/Project Zomboid Modding Tools/Tiles/
 *                          PNGs in this folder are scanned directly to discover
 *                          tileset names and image dimensions.
 *
 * ── How tileset discovery works ──────────────────────────────────────────────
 *   For every *.png in <tilesdir> (non-recursive):
 *     name     = filename without ".png"   (e.g. "advertising_01")
 *     columns  = image pixel width  / TILE_IMG_W   (64)
 *     rows     = image pixel height / TILE_IMG_H   (128)
 *     tileCount = columns × rows
 *     GID block = tileCount   (no padding — matches WorldEd exactly)
 *   Files are sorted alphabetically before GID assignment, matching the order
 *   WorldEd uses when it indexes the same folder.
 *
 * ── TMX output (confirmed from real WorldEd files) ───────────────────────────
 *   <map version="1.0" orientation="levelisometric" width="300" height="300"
 *        tilewidth="64" tileheight="32">
 *    <tileset firstgid="1" name="advertising_01" tilewidth="64" tileheight="128">
 *     <image source="../Tiles/advertising_01.png" width="512" height="2048"/>
 *    </tileset>
 *    ...
 *    <layer name="0_Floor" width="300" height="300">
 *     <data encoding="base64" compression="zlib">…</data>
 *    </layer>
 *
 * ── Usage ────────────────────────────────────────────────────────────────────
 *   Map folder mode (recommended) — processes ALL cells in a map directory:
 *     java run.java \
 *       --mapdir    path/to/map/folder \
 *       --tilesdir  "Steam/.../Project Zomboid Modding Tools/Tiles" \
 *       [--output   path/to/output/folder]
 *
 *   The map folder must contain files named X_Y.lotheader and world_X_Y.lotpack
 *   (and optionally chunkdata_X_Y.bin) for each cell X,Y.  Every cell found is
 *   processed and produces MyMap_X_Y.tmx in the output folder (default: same as
 *   --mapdir).  Buildings go in <outdir>/buildings/X_Y_building_i.tbx.
 *
 *   Individual file mode (single cell):
 *     java run.java \
 *       --lotheader  0_0.lotheader \
 *       --lotpack    world_0_0.lotpack \
 *       [--output    MyMap_0_0.tmx]
 *
 *   Optional for exact GID compatibility with an existing project:
 *       --tilesets  <any_existing_MyMap_X_Y.tmx>   (read tileset registry from existing TMX)
 *       --tilesdir  <Tiles folder>                   (scan Tiles folder for image dimensions)
 *       --pzw       <name>                           (WorldEd project filename, no extension;
 *                                                     default "untitled" → untitled.pzw)
 *
 *       --objects   <objects.lua>                 (optional: zone/object file → imported into .pzw)
 *       --excludebuilding                         (omit all building-specific tile layers and .tbx
 *                                                  files; the lowest Floor sublayer of each building
 *                                                  tile is kept in the TMX so ground surfaces are
 *                                                  preserved after compaction)
 *   Without --tilesets or --tilesdir, tileset block sizes are computed from the tile
 *   names in the lotheader. The result is a self-consistent TMX that renders correctly
 *   in TileZed/WorldEd but may have different firstGid values from other cells.
 */

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;
import java.util.Base64;
import javax.imageio.ImageIO;

public class run {

    // Map display tile size (goes on <map>)
    static final int MAP_TILE_W  = 64;
    static final int MAP_TILE_H  = 32;
    // Tileset image tile size (goes on <tileset>, drives pixel-dim calculation)
    static final int TILE_IMG_W  = 64;
    static final int TILE_IMG_H  = 128;
    // Every cell TMX is declared as 300×300 regardless of actual chunk count
    static final int CELL_TILES  = 300;

    static final String[] SUBLAYERS = {
        "Floor", "FloorOverlay", "FloorOverlay2",
        "FloorOverlay3", "FloorOverlay4", "Vegetation"
    };
    static final int NUM_SUBLAYERS = SUBLAYERS.length; // 6

    // Lotpack squares can occasionally carry more than NUM_SUBLAYERS tile entries
    // (the PZ engine writes however many entries exist; there is no hardcoded cap).
    // These "overflow" entries (index >= NUM_SUBLAYERS) have no corresponding TBX
    // sublayer and must stay in the TMX.  We reserve 8 extra slots to be safe;
    // they are written to the TMX as additional layers named "0_Extra0", "0_Extra1", …
    static final int OVERFLOW_SUBLAYERS = 8;
    static final int NUM_ALL_SUBLAYERS  = NUM_SUBLAYERS + OVERFLOW_SUBLAYERS; // 14

    // ── data classes ─────────────────────────────────────────────────────────

    // Pixel multiplier for levelisometric: BOTH x and y use tileHeight (32)
    // From mapwriter.cpp TileToPixelCoordinates - levelisometric uses tileHeight for both axes.
    // Lot position pixel formula (confirmed from ZLevelRenderer / MapWriter source):
    //   pixel_x = tile_x * MAP_TILE_W   (lotheader rr->x is in tile-width units, 64px each)
    //   pixel_y = tile_y * MAP_TILE_H   (lotheader rr->y is in tile-height units, 32px each)
    //   lot width  = (bw+1) * MAP_TILE_W
    //   lot height = (bh+1) * MAP_TILE_H

    static class LotRoom {
        String name;
        int floor;                    // z-level
        List<int[]> rects = new ArrayList<>();   // each int[] = {x, y, w, h} in cell tile space
    }

    static class LotBuilding {
        List<Integer> roomIDs = new ArrayList<>();  // indices into LotHeader.rooms
    }

    static class LotHeader {
        int version;
        List<String> tileNames = new ArrayList<>();
        int chunkW, chunkH;   // tile dimensions of one chunk (CHUNK_WIDTH/HEIGHT) -- typically 10
        int numLevels;
        List<LotRoom>     rooms     = new ArrayList<>();
        List<LotBuilding> buildings = new ArrayList<>();
        byte[] spawnBytes = new byte[0]; // 30×30 = 900 bytes, x-outer y-inner
        // chunks per axis = 300 / chunkW  (= 30 for standard cells)
        int chunksX() { return CELL_TILES / chunkW; }
        int chunksY() { return CELL_TILES / chunkH; }
    }

    /** One extracted building: bounding box + all-level tile data + room layout. */
    static class BuildingData {
        int index;           // building index (for filename)
        int ox, oy;          // cell-local origin (tile coords)
        int bw, bh;          // bounding-box dimensions (tiles)
        // roomGrid[z][y][x] = 1-based room index within this building (0 = no room)
        int[][][] roomGrid;  // [numLevels][bh][bw]
        List<String> roomNames = new ArrayList<>();   // index 1-based (index 0 = sentinel "")
        // tile data per level/sublayer within the bounding box
        // buildingTiles[z][sl][by][bx] = lotheader tileID (-1 = empty)
        int[][][][] buildingTiles;  // [numLevels][NUM_SUBLAYERS][bh][bw]
    }

    /**
     * Info discovered from a single PNG in the Tiles folder.
     * name      = filename without ".png"
     * filename  = filename with ".png"   (used in <image source>)
     * imgW/imgH = actual pixel dimensions of the PNG
     * tileCount = (imgW / TILE_IMG_W) * (imgH / TILE_IMG_H)
     */
    record TilesetInfo(String name, String filename, String sourcePath, int imgW, int imgH, int firstGid) {
        // Convenience constructor for Tiles-folder scans (sourcePath auto-derived, firstGid=0)
        TilesetInfo(String name, String filename, int imgW, int imgH) {
            this(name, filename, "../Tiles/" + filename, imgW, imgH, 0);
        }
        int columns()   { return imgW / TILE_IMG_W; }
        int rows()      { return imgH / TILE_IMG_H; }
        int tileCount() { return columns() * rows(); }
    }

    // Read all <tileset> definitions from an existing project TMX.
    // This gives us the exact firstGid values (including mod tilesets) so our
    // output GIDs will be bit-for-bit identical to those in the project.
    static Map<String, TilesetInfo> readTilesetsFromTmx(Path tmxPath) throws IOException {
        String xml = Files.readString(tmxPath);
        Map<String, TilesetInfo> result = new LinkedHashMap<>();
        // Match: <tileset firstgid="N" name="NAME" ...> <image source="..." width="W" height="H"/>
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
            "<tileset\\s+firstgid=\"(\\d+)\"\\s+name=\"([^\"]+)\"[^>]*>\\s*" +
            "<image\\s+source=\"([^\"]+)\"[^>]*width=\"(\\d+)\"[^>]*height=\"(\\d+)\"",
            java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = pat.matcher(xml);
        while (m.find()) {
            int    firstGid = Integer.parseInt(m.group(1));
            String name     = xmlUnescape(m.group(2));
            String source   = xmlUnescape(m.group(3));  // e.g. "../../Tiles/foo.png"
            int    imgW     = Integer.parseInt(m.group(4));
            int    imgH     = Integer.parseInt(m.group(5));
            // Derive filename from source path (just the filename part)
            String filename = source.contains("/") ? source.substring(source.lastIndexOf('/') + 1)
                                                   : source;
            result.put(name, new TilesetInfo(name, filename, source, imgW, imgH, firstGid));
        }
        return result;
    }

    static String xmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'");
    }

    // ── main ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);

        Path tilesDir    = opts.containsKey("tilesdir") ? Path.of(opts.get("tilesdir")) : null;
        Path tilesetsRef = opts.containsKey("tilesets") ? Path.of(opts.get("tilesets")) : null;
        boolean excludeBuilding = opts.containsKey("excludebuilding");

        // ── Map-folder mode: process every cell found in the directory ─────────
        if (opts.containsKey("mapdir")) {
            Path mapDir = Path.of(opts.get("mapdir"));
            if (!Files.isDirectory(mapDir))
                die("--mapdir is not a directory: " + mapDir);

            // Output folder: --output overrides, otherwise same as mapdir.
            Path outDir = opts.containsKey("output") ? Path.of(opts.get("output")) : mapDir;
            Files.createDirectories(outDir);

            // PZW project name: --pzw <name> (no extension), default "untitled".
            String pzwName = opts.getOrDefault("pzw", "untitled");

            // Optional objects.lua: zone/object data imported into .pzw.
            // Resolution order:
            //   1. --objects <path>  explicit path
            //   2. <mapdir>/objects.lua  auto-detected if present
            //   3. nothing (zones omitted — always valid, not an error)
            List<LuaZone> luaZones = new ArrayList<>();
            {
                Path objPath = null;
                if (opts.containsKey("objects")) {
                    objPath = Path.of(opts.get("objects"));
                    if (!Files.exists(objPath)) {
                        System.err.println("WARNING: --objects file not found: " + objPath + " — skipping zones.");
                        objPath = null;
                    }
                } else {
                    Path candidate = mapDir.resolve("objects.lua");
                    if (Files.exists(candidate)) {
                        objPath = candidate;
                        log("  Auto-detected " + candidate.getFileName() + " in map folder.");
                    }
                }
                if (objPath != null) {
                    try {
                        luaZones = parseLuaObjects(Files.readString(objPath));
                        log("  Loaded " + luaZones.size() + " zone(s) from " + objPath.getFileName());
                    } catch (Exception e) {
                        System.err.println("WARNING: Could not parse objects.lua: " + e.getMessage() + " — skipping zones.");
                    }
                }
            }

            // Discover all lotheader files; each one defines a cell.
            List<Path> lotheaders;
            try (var ds = Files.newDirectoryStream(mapDir, "*.lotheader")) {
                lotheaders = new ArrayList<>();
                ds.forEach(lotheaders::add);
            }
            // Sort for deterministic processing order (x-major, then y).
            lotheaders.sort((a, b) -> {
                int[] ca = parseCellCoords(a.getFileName().toString());
                int[] cb = parseCellCoords(b.getFileName().toString());
                return ca[0] != cb[0] ? Integer.compare(ca[0], cb[0]) : Integer.compare(ca[1], cb[1]);
            });
            if (lotheaders.isEmpty())
                die("No *.lotheader files found in: " + mapDir);

            // Compute map extents from all cell coords.
            int minCX = Integer.MAX_VALUE, minCY = Integer.MAX_VALUE;
            int maxCX = Integer.MIN_VALUE, maxCY = Integer.MIN_VALUE;
            List<int[]> allCoords = new ArrayList<>();
            for (Path lh : lotheaders) {
                int[] cc = parseCellCoords(lh.getFileName().toString());
                allCoords.add(cc);
                minCX = Math.min(minCX, cc[0]); minCY = Math.min(minCY, cc[1]);
                maxCX = Math.max(maxCX, cc[0]); maxCY = Math.max(maxCY, cc[1]);
            }

            log("Found " + lotheaders.size() + " cell(s) in " + mapDir);
            log("  World extent: cells (" + minCX + "," + minCY + ") to (" + maxCX + "," + maxCY + ")");

            // Process each cell: TMX + TBX files.
            List<int[]> processedCells = new ArrayList<>();
            // cellX_cellY → 900 spawn bytes for PNG reconstruction
            Map<String, byte[]> spawnDataMap = new LinkedHashMap<>();
            int processed = 0, failed = 0;
            for (Path lh : lotheaders) {
                int[] cc = parseCellCoords(lh.getFileName().toString());
                String cellTag = cc[0] + "_" + cc[1];
                Path lp = mapDir.resolve("world_" + cellTag + ".lotpack");
                if (!Files.exists(lp)) {
                    System.err.println("WARNING: No lotpack for " + lh.getFileName()
                        + " (expected " + lp.getFileName() + ") — skipping.");
                    failed++;
                    continue;
                }
                Path outTmx = outDir.resolve("MyMap_" + cellTag + ".tmx");
                try {
                    log("\n== Cell " + cellTag + " ==================================");
                    LotHeader hdr = processCell(lh, lp, outTmx, tilesDir, tilesetsRef, excludeBuilding);
                    processedCells.add(cc);
                    if (hdr.spawnBytes.length == 900)
                        spawnDataMap.put(cellTag, hdr.spawnBytes);
                    processed++;
                } catch (Exception e) {
                    System.err.println("ERROR processing cell " + cellTag + ": " + e.getMessage());
                    failed++;
                }
            }

            // Write the WorldEd project file (.pzw).
            Path pzwPath = outDir.resolve(pzwName + ".pzw");
            writePzw(pzwPath, pzwName, processedCells, minCX, minCY, maxCX, maxCY, outDir, luaZones);

            // Reconstruct merged ZombieSpawnMap PNG from lotheader spawn bytes.
            Path spawnPng = outDir.resolve(pzwName + "_ZombieSpawnMap.png");
            writeZombieSpawnMap(spawnPng, spawnDataMap, minCX, minCY, maxCX, maxCY);

            log("\nDone. " + processed + " cell(s) written"
                + (failed > 0 ? ", " + failed + " skipped/failed" : "")
                + ". Project: " + pzwPath);
            return;
        }

        // ── Single-cell file mode ─────────────────────────────────────────────
        Path lotheaderPath, lotpackPath;
        if (opts.containsKey("lotheader") && opts.containsKey("lotpack")) {
            lotheaderPath = Path.of(opts.get("lotheader"));
            lotpackPath   = Path.of(opts.get("lotpack"));
        } else {
            System.err.println("Usage (map folder mode — processes all cells):");
            System.err.println("  java run.java \\");
            System.err.println("    --mapdir    <folder with X_Y.lotheader + world_X_Y.lotpack files> \\");
            System.err.println("    --tilesets  <any existing MyMap_X_Y.tmx from this project> \\");
            System.err.println("    [--output   <output folder, default: same as --mapdir>] \\\\");
            System.err.println("    [--pzw      <project name, no extension, default: untitled>] \\\\");
            System.err.println("    [--objects  <objects.lua path, optional>]") ;
            System.err.println("    [--excludebuilding]                   (exclude building tiles from TMX; no .tbx output)");
            System.err.println();
            System.err.println("Usage (single-cell file mode):");
            System.err.println("  java run.java \\");
            System.err.println("    --lotheader <X_Y.lotheader> \\");
            System.err.println("    --lotpack   <world_X_Y.lotpack> \\");
            System.err.println("    --tilesets  <any existing MyMap_X_Y.tmx from this project> \\");
            System.err.println("    [--output   <MyMap_X_Y.tmx>]");
            System.err.println();
            System.err.println("  --tilesdir <Tiles folder with PNGs> also accepted instead of --tilesets");
            System.exit(1);
            return;
        }
        String outName = opts.getOrDefault("output", deriveOutputName(lotheaderPath));
        processCell(lotheaderPath, lotpackPath, Path.of(outName), tilesDir, tilesetsRef, excludeBuilding);
        log("\nDone! Open " + outName + " in WorldEd / TileZed.");
    }

    // ── processCell: convert one (lotheader, lotpack) pair → TMX + TBX files ─

    static LotHeader processCell(Path lotheaderPath, Path lotpackPath, Path outPath,
                            Path tilesDir, Path tilesetsRef,
                            boolean excludeBuilding) throws Exception {

        // Parse cell coordinates from the lotheader filename: "X_Y.lotheader"
        int[] cellCoords = parseCellCoords(lotheaderPath.getFileName().toString());
        int cellX = cellCoords[0];  // world-space cell X (used to normalise tile coords)
        int cellY = cellCoords[1];
        log("      Cell coordinates: " + cellX + "," + cellY);

        // 1 — lotheader
        log("[1/5] Parsing " + lotheaderPath + " ...");
        LotHeader header = parseLotHeader(lotheaderPath);
        log("      Tiles: " + header.tileNames.size()
          + "  ChunkSize: " + header.chunkW + "x" + header.chunkH
          + "  Chunks: " + header.chunksX() + "x" + header.chunksY()
          + "  Levels: " + header.numLevels);

        // 2 — build tileset registry
        log("[2/5] Building tileset registry ...");
        Map<String, TilesetInfo> tilesetRegistry = new LinkedHashMap<>();
        // Secondary registry from tilesDir: used as fallback to resolve subfolder
        // paths (e.g. "2x/") for tilesets absent from the reference TMX.
        Map<String, TilesetInfo> tilesDirRegistry = new LinkedHashMap<>();
        if (tilesetsRef != null) {
            tilesetRegistry = readTilesetsFromTmx(tilesetsRef);
            log("      " + tilesetRegistry.size() + " tilesets read from " + tilesetsRef);
            if (tilesetRegistry.isEmpty())
                log("      WARNING: No tilesets found in reference TMX.");
            if (tilesDir != null) {
                tilesDirRegistry = scanTilesDir(tilesDir);
                log("      " + tilesDirRegistry.size() + " tileset PNGs found in " + tilesDir
                    + " (subfolder fallback for tilesets absent from reference TMX)");
            }
        } else if (tilesDir != null) {
            tilesetRegistry = scanTilesDir(tilesDir);
            log("      " + tilesetRegistry.size() + " tileset PNGs found in " + tilesDir);
            if (tilesetRegistry.isEmpty())
                log("      WARNING: No PNGs found — tileset image info will be absent.");
        } else {
            log("      No --tilesets or --tilesdir supplied.");
            log("      GIDs will be computed from tile names (self-consistent, may differ from project).");
            log("      Use --tilesets <any_existing_MyMap_X_Y.tmx> to match an existing project's GIDs.");
        }

        // 3 — lotpack
        log("[3/5] Parsing " + lotpackPath + " ...");
        int cellW = header.chunksX() * header.chunkW;  // = 300
        int cellH = header.chunksY() * header.chunkH;
        // tiles[level][sublayer][y][x] = lotheader tileID  (-1 = empty)
        // Slots 0..NUM_SUBLAYERS-1  = 6 named sublayers copied to .tbx for buildings.
        // Slots NUM_SUBLAYERS..NUM_ALL_SUBLAYERS-1 = overflow entries that always stay in TMX.
        int[][][][] tiles = new int[header.numLevels][NUM_ALL_SUBLAYERS][cellH][cellW];
        for (int[][][] lv : tiles)
            for (int[][] sl : lv)
                for (int[] row : sl)
                    Arrays.fill(row, -1);
        // roomIds[z][y][x] = room ID per tile per level (-1 = exterior/terrain)
        int[][][] roomIds = new int[header.numLevels][cellH][cellW];
        for (int[][] lv : roomIds)
            for (int[] row : lv) Arrays.fill(row, -1);
        parseLotpack(lotpackPath, header, tiles, roomIds);
        log("      Grid decoded: " + cellW + " x " + cellH + " tiles");

        // 4 — extract buildings, write .tbx files, erase building tiles from grid
        // NOTE: lotheader room rects are ALWAYS in cell-local tile space [0,300).
        // No world-origin subtraction needed.
        log("[4/5] Extracting buildings ...");
        List<BuildingData> buildings = extractBuildings(header, tiles, roomIds, excludeBuilding);

        // .tbx files go in a "buildings" subfolder next to the output TMX.
        // Named  X_Y_building_i.tbx  so multiple cells can share one folder.
        Path buildingsDir = outPath.getParent() != null
                ? outPath.getParent().resolve("buildings")
                : Path.of("buildings");
        if (!buildings.isEmpty())
            Files.createDirectories(buildingsDir);

        String cellTag = cellX + "_" + cellY;  // used in tbx filenames
        List<String> lotEntries = new ArrayList<>();
        if (!excludeBuilding) {
            for (BuildingData bd : buildings) {
                String tbxName = cellTag + "_building_" + bd.index + ".tbx";
                Path tbxPath   = buildingsDir.resolve(tbxName);
                writeTbx(tbxPath, bd, header);
                String relTbx;
                try {
                    Path tmxDir = outPath.toAbsolutePath().getParent();
                    relTbx = tmxDir != null
                           ? "./" + tmxDir.relativize(tbxPath.toAbsolutePath()).toString().replace('\\', '/')
                           : "./buildings/" + tbxName;
                } catch (Exception e) {
                    relTbx = "./buildings/" + tbxName;
                }
                lotEntries.add(relTbx + "|" + bd.ox + "|" + bd.oy + "|" + bd.bw + "|" + bd.bh);
            }
            log("      " + buildings.size() + " building(s) written to " + buildingsDir);
        } else {
            log("      " + buildings.size() + " building(s) detected; skipping .tbx output (--excludebuilding).");
        }

        // 5 — write TMX (building tiles already erased from 'tiles' by extractBuildings)
        log("[5/5] Writing " + outPath + " ...");
        writeTmx(outPath, header, tiles, tilesetRegistry, tilesDirRegistry, lotEntries, excludeBuilding);
        return header;
    }

    // ── Tiles folder scanner ──────────────────────────────────────────────────
    //
    // Walks <tilesdir> recursively, collecting all *.png files.
    // Files are sorted alphabetically by name (not path) — this matches the
    // order WorldEd uses when it indexes the same folder.
    // The tileset name is the filename without extension (e.g. "blends_natural_01"),
    // which is what the lotheader tile strings reference.
    // The <image source> is relative to the TMX's directory: "../Tiles/<relpath>"
    // where <relpath> is the path of the PNG relative to <tilesdir>.

    static Map<String, TilesetInfo> scanTilesDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir))
            throw new IOException("--tilesdir is not a directory: " + dir);

        List<Path> pngs;
        try (Stream<Path> s = Files.walk(dir)) {
            pngs = s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                    // Sort by filename only (case-sensitive, matching WorldEd GID order).
                    // Ties (same filename in root vs subfolder) are broken by depth descending
                    // so that the subfolder copy always wins — subfolders like "2x/" hold the
                    // current authoritative version when both a root and a subfolder copy exist.
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString())
                        .thenComparingInt(p -> -(dir.relativize(p).getNameCount())))
                    .toList();
        }

        Map<String, TilesetInfo> result = new LinkedHashMap<>();
        for (Path png : pngs) {
            String filename = png.getFileName().toString();
            String name     = filename.substring(0, filename.length() - 4); // strip ".png"
            // relative path from tilesdir → used in <image source="../Tiles/...">
            String relPath  = dir.relativize(png).toString().replace('\\', '/');

            if (result.containsKey(name)) {
                log("      WARNING: duplicate tileset name '" + name + "' (keeping subfolder copy, skipping " + relPath + ")");
                continue;
            }

            int[] dims = readPngDimensions(png);
            if (dims == null) {
                log("      WARNING: could not read image dimensions for " + relPath + ", skipping");
                continue;
            }
            int w = dims[0], h = dims[1];
            if (w % TILE_IMG_W != 0 || h % TILE_IMG_H != 0) {
                log("      WARNING: " + relPath + " size " + w + "x" + h
                    + " is not a multiple of " + TILE_IMG_W + "x" + TILE_IMG_H);
            }
            result.put(name, new TilesetInfo(name, relPath, w, h));
        }
        return result;
    }

    /**
     * Reads width and height from an image file.
     * Supports true PNG (magic: 89 50 4E 47) and JPEG-encoded files with any
     * extension (magic: FF D8), which occur in PZ mod tile packs where JPEG data
     * is saved with a .png extension.
     *
     * PNG: parses the IHDR chunk (bytes 16-23).
     * JPEG: scans for SOF0/SOF1/SOF2 markers (FF C0 / FF C1 / FF C2) which
     *       carry the frame height and width.
     *
     * Returns [width, height] or null on any parse error.
     */
    static int[] readPngDimensions(Path png) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(png))) {
            byte[] magic = new byte[2];
            if (in.read(magic) != 2) return null;

            // ── PNG: 89 50 ────────────────────────────────────────────────
            if ((magic[0] & 0xFF) == 0x89 && (magic[1] & 0xFF) == 0x50) {
                // Skip remaining 6 signature bytes + 4 chunk-length bytes = 10 more
                if (in.skip(10) != 10) return null;
                byte[] buf = new byte[4];
                // Chunk type — must be "IHDR"
                if (in.read(buf) != 4) return null;
                if (buf[0] != 'I' || buf[1] != 'H' || buf[2] != 'D' || buf[3] != 'R') return null;
                // Width (4 bytes big-endian)
                if (in.read(buf) != 4) return null;
                int w = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16)
                      | ((buf[2] & 0xFF) << 8)  |  (buf[3] & 0xFF);
                // Height (4 bytes big-endian)
                if (in.read(buf) != 4) return null;
                int h = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16)
                      | ((buf[2] & 0xFF) << 8)  |  (buf[3] & 0xFF);
                return new int[]{ w, h };
            }

            // ── JPEG: FF D8 ───────────────────────────────────────────────
            if ((magic[0] & 0xFF) == 0xFF && (magic[1] & 0xFF) == 0xD8) {
                // Scan markers until we find SOF0 (FF C0), SOF1 (FF C1), or SOF2 (FF C2).
                // Each marker: FF xx, then 2-byte big-endian payload length (includes the 2 length bytes).
                byte[] buf2 = new byte[2];
                while (true) {
                    // Find next FF marker byte
                    int b = in.read();
                    if (b < 0) return null;
                    if ((b & 0xFF) != 0xFF) continue;
                    // Read marker type
                    int marker = in.read();
                    if (marker < 0) return null;
                    // SOF markers that carry frame dimensions
                    if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                        // Payload: 2-byte length, 1-byte precision, 2-byte height, 2-byte width
                        if (in.skip(2) < 0) return null; // skip length
                        if (in.skip(1) < 0) return null; // skip precision (bits/sample)
                        if (in.read(buf2) != 2) return null;
                        int h = ((buf2[0] & 0xFF) << 8) | (buf2[1] & 0xFF);
                        if (in.read(buf2) != 2) return null;
                        int w = ((buf2[0] & 0xFF) << 8) | (buf2[1] & 0xFF);
                        return new int[]{ w, h };
                    }
                    // Skip this segment: read 2-byte length, then skip (length - 2) bytes
                    if (in.read(buf2) != 2) return null;
                    int segLen = ((buf2[0] & 0xFF) << 8) | (buf2[1] & 0xFF);
                    int skip = segLen - 2;
                    if (skip > 0) in.skip(skip);
                }
            }

            return null; // unknown format
        } catch (IOException e) {
            return null;
        }
    }

    // ── lotheader parser ──────────────────────────────────────────────────────
    // Reads version, tile names, grid dimensions, and numLevels.
    // Rooms, buildings and zombie-spawn grid are read and discarded — we don't
    // need them for TMX tile layer output, and skipping them avoids any risk of
    // parse errors in unusual files.

    static LotHeader parseLotHeader(Path path) throws IOException {
        LotHeader h = new LotHeader();
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path)))) {
            h.version    = readInt32LE(in);
            int numTiles = readInt32LE(in);
            for (int i = 0; i < numTiles; i++) h.tileNames.add(readPzString(in));
            in.readByte();                   // padding uint8(0)
            h.chunkW    = readInt32LE(in);   // CHUNK_WIDTH  -- tile width of one chunk (10)
            h.chunkH    = readInt32LE(in);   // CHUNK_HEIGHT -- tile height of one chunk (10)
            h.numLevels = readInt32LE(in);

            // Rooms -- read and KEEP for building extraction
            int numRooms = readInt32LE(in);
            for (int i = 0; i < numRooms; i++) {
                LotRoom room = new LotRoom();
                room.name  = readPzString(in);
                room.floor = readInt32LE(in);
                int nRect  = readInt32LE(in);
                for (int r = 0; r < nRect; r++) {
                    int rx = readInt32LE(in), ry = readInt32LE(in);
                    int rw = readInt32LE(in), rh = readInt32LE(in);
                    room.rects.add(new int[]{rx, ry, rw, rh});
                }
                int nObj = readInt32LE(in);
                for (int o = 0; o < nObj * 3; o++) readInt32LE(in);  // metaEnum,x,y -- discard
                h.rooms.add(room);
            }
            // Buildings -- read and KEEP
            int numBuildings = readInt32LE(in);
            for (int i = 0; i < numBuildings; i++) {
                LotBuilding bld = new LotBuilding();
                int n = readInt32LE(in);
                for (int j = 0; j < n; j++) bld.roomIDs.add(readInt32LE(in));
                h.buildings.add(bld);
            }
            // Zombie spawn grid: 30×30 = 900 bytes, x-outer y-inner
            // Each byte = red channel of the ZombieSpawnMap PNG at (cellX*30+x, cellY*30+y)
            h.spawnBytes = in.readNBytes(900);
        }
        return h;
    }

    // ── lotpack parser ────────────────────────────────────────────────────────
    // Cell layout:
    //   300×300 tiles per cell
    //   30×30 chunks per cell  (lotW=30, lotH=30 as read from lotheader)
    //   10×10 tiles per chunk  (CHUNK_WIDTH=10, CHUNK_HEIGHT=10 in C++)
    //
    // From generateCell() in lotfilesmanager.cpp:
    //   int32  numChunks  (= 30*30 = 900)
    //   900 × int64  offsets  (backfilled after chunk data)
    //   chunks written in x-major order: for cx in [0,30), for cy in [0,30)
    //
    // Each chunk block = raw (not zlib) RLE int32 stream:
    //   for z in [0,MaxLevel), for x in [0,10), for y in [0,10):
    //     empty run   → int32(-1), int32(runLen)
    //     non-empty   → int32(n), int32(tileID)×n
    //                   where n = entry count (NOT n+1), NO roomID field
    //                   tileID = index into lotheader tile name list, or -1 for empty sublayer

    static final int CHUNK_W = 10;  // tiles per chunk (matches C++ CHUNK_WIDTH)
    static final int CHUNK_H = 10;

    static void parseLotpack(Path path, LotHeader header, int[][][][] tiles, int[][][] roomIds) throws IOException {
        int cntX = header.chunksX();  // 30
        int cntY = header.chunksY();  // 30
        int expectedChunks = cntX * cntY;  // 900
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            int numChunks = readInt32LE(raf);
            if (numChunks != expectedChunks)
                throw new IOException("Lotpack: expected " + expectedChunks
                    + " chunks (" + cntX + "x" + cntY + "), got " + numChunks);

            long[] offsets = new long[numChunks];
            for (int i = 0; i < numChunks; i++) offsets[i] = readInt64LE(raf);

            int idx = 0;
            for (int cx = 0; cx < cntX; cx++)
                for (int cy = 0; cy < cntY; cy++) {
                    raf.seek(offsets[idx++]);
                    decodeChunk(raf, cx, cy, header, tiles, roomIds);
                }
        }
    }

    static void decodeChunk(RandomAccessFile raf, int cx, int cy,
                            LotHeader hdr, int[][][][] tiles, int[][][] roomIds) throws IOException {
        int cellW = hdr.chunksX() * hdr.chunkW;  // 300
        int cellH = hdr.chunksY() * hdr.chunkH;
        int baseX = cx * hdr.chunkW;
        int baseY = cy * hdr.chunkH;

        // RLE stream: for z in [0,MaxLevel), for x in [0,chunkW), for y in [0,chunkH)
        // Each square (from lotfilesmanager.cpp generateChunk):
        //   int32(-1), int32(runLen)            → skip runLen empty squares
        //   int32(n+1), int32(roomID), int32*n  → n tile entries (roomID discarded on reverse)
        // n = entries.count(), first field = n+1, second field = roomID.
        int skip = 0;  // global across all levels — RLE runs can span level boundaries
        for (int z = 0; z < hdr.numLevels; z++) {
            for (int x = 0; x < hdr.chunkW; x++) {
                for (int y = 0; y < hdr.chunkH; y++) {
                    if (skip > 0) { skip--; continue; }
                    int first = readInt32LE(raf);
                    if (first == -1) {
                        skip = readInt32LE(raf) - 1;
                    } else {
                        int n  = first - 1;           // entry count = n+1 field minus 1
                        int roomId = readInt32LE(raf); // roomID from lotpack
                        int wx = baseX + x, wy = baseY + y;
                        // Store roomId for every z-level — the lotpack encodes it per square per level.
                        // roomId == -1 = exterior/terrain; >= 0 = inside that room.
                        if (roomIds != null && z < roomIds.length && wy < roomIds[z].length && wx < roomIds[z][wy].length)
                            roomIds[z][wy][wx] = roomId;
                        for (int e = 0; e < n; e++) {
                            int tid = readInt32LE(raf);
                            if (tid < 0) continue;    // -1 = empty sublayer, skip
                            // Store all entries up to NUM_ALL_SUBLAYERS.
                            // Entries [0..NUM_SUBLAYERS-1] are the 6 named sublayers (tbx-eligible).
                            // Entries [NUM_SUBLAYERS..] are overflow; they stay in TMX always.
                            if (e >= NUM_ALL_SUBLAYERS) continue;  // extremely unlikely; ignore beyond cap
                            if (wx < cellW && wy < cellH && tiles[z][e][wy][wx] < 0)
                                tiles[z][e][wy][wx] = tid;
                        }
                    }
                }
            }
        }
    }

    // ── Building extraction ───────────────────────────────────────────────────
    // For each LotBuilding in the lotheader:
    //   1. Compute bounding box across all room rects (cell tile space).
    //   2. Copy tile data from the main 'tiles' array into building-local arrays.
    //   3. Erase those tiles from the main 'tiles' array (buildings become lots).
    //   4. Build building-local room grid (for .tbx <rooms> CSV).
    // Returns list of BuildingData objects, one per building.

    static List<BuildingData> extractBuildings(LotHeader header, int[][][][] tiles, int[][][] roomIds, boolean excludeBuilding) {
        List<BuildingData> result = new ArrayList<>();
        int numLevels = header.numLevels;
        int cellH = roomIds[0].length;
        int cellW = roomIds[0][0].length;

        // Build room-to-building index: roomIndex → buildingIndex
        int[] roomToBuilding = new int[header.rooms.size()];
        Arrays.fill(roomToBuilding, -1);
        for (int bi = 0; bi < header.buildings.size(); bi++)
            for (int rid : header.buildings.get(bi).roomIDs)
                roomToBuilding[rid] = bi;

        int numBuildings = header.buildings.size();

        // Compute per-building bounding box from all room rects across ALL levels.
        int[] minX = new int[numBuildings]; Arrays.fill(minX, Integer.MAX_VALUE);
        int[] minY = new int[numBuildings]; Arrays.fill(minY, Integer.MAX_VALUE);
        int[] maxX = new int[numBuildings]; Arrays.fill(maxX, Integer.MIN_VALUE);
        int[] maxY = new int[numBuildings]; Arrays.fill(maxY, Integer.MIN_VALUE);

        for (int bi = 0; bi < numBuildings; bi++) {
            for (int rid : header.buildings.get(bi).roomIDs) {
                LotRoom room = header.rooms.get(rid);
                for (int[] rc : room.rects) {
                    // rc[0..1] are already in local cell space [0,300).
                    minX[bi] = Math.min(minX[bi], rc[0]);
                    minY[bi] = Math.min(minY[bi], rc[1]);
                    maxX[bi] = Math.max(maxX[bi], rc[0] + rc[2]);
                    maxY[bi] = Math.max(maxY[bi], rc[1] + rc[3]);
                }
            }
        }

        // Expand every building's bbox by 1 tile in all 4 directions (guaranteed exterior margin).
        // This matches TileZed behaviour: building files always have at least one tile of
        // empty exterior space around the room-rect extents, so that south/east walls,
        // driveway tiles, and exterior decorations are captured in the .tbx.
        // Guard: don't step into another building's room-rect zone or out of the cell.
        for (int bi = 0; bi < numBuildings; bi++) {
            if (minX[bi] > maxX[bi]) continue;
            // Save room-rect extents before expansion for guard checks below.
            int rrMinX = minX[bi], rrMinY = minY[bi], rrMaxX = maxX[bi], rrMaxY = maxY[bi];

            // North (-1 row)
            {
                int ny = minY[bi] - 1;
                boolean blocked = false;
                if (ny < 0) blocked = true;
                for (int b = 0; b < numBuildings && !blocked; b++)
                    if (b != bi && ny >= minX[b] && ny <= maxY[b]  // rough check, refined below
                        && ny >= minY[b] && ny <= maxY[b])
                        for (int gx = minX[bi]; gx <= maxX[bi] && !blocked; gx++)
                            if (gx >= minX[b] && gx <= maxX[b]) blocked = true;
                if (!blocked) minY[bi] = ny;
            }
            // South (+1 row)
            {
                int sy = maxY[bi] + 1;
                boolean blocked = false;
                if (sy >= cellH) blocked = true;
                for (int b = 0; b < numBuildings && !blocked; b++)
                    if (b != bi && sy >= minY[b] && sy <= maxY[b])
                        for (int gx = minX[bi]; gx <= maxX[bi] && !blocked; gx++)
                            if (gx >= minX[b] && gx <= maxX[b]) blocked = true;
                if (!blocked) maxY[bi] = sy;
            }
            // West (-1 col)
            {
                int wx = minX[bi] - 1;
                boolean blocked = false;
                if (wx < 0) blocked = true;
                for (int b = 0; b < numBuildings && !blocked; b++)
                    if (b != bi && wx >= minX[b] && wx <= maxX[b])
                        for (int gy = minY[bi]; gy <= maxY[bi] && !blocked; gy++)
                            if (gy >= minY[b] && gy <= maxY[b]) blocked = true;
                if (!blocked) minX[bi] = wx;
            }
            // East (+1 col)
            {
                int ex = maxX[bi] + 1;
                boolean blocked = false;
                if (ex >= cellW) blocked = true;
                for (int b = 0; b < numBuildings && !blocked; b++)
                    if (b != bi && ex >= minX[b] && ex <= maxX[b])
                        for (int gy = minY[bi]; gy <= maxY[bi] && !blocked; gy++)
                            if (gy >= minY[b] && gy <= maxY[b]) blocked = true;
                if (!blocked) maxX[bi] = ex;
            }
        }


        // tileBuilding[z][y][x] = building index that owns this tile at this level, or -1.
        //
        //   Pass 1 — roomId >= 0: the square is inside a room rect.
        //            Look up which building owns that room → assign directly (authoritative).
        //
        //   Pass 2 — roomId == -1 and tile has data: assign to any building whose bounding
        //            box [minX..maxX] × [minY..maxY] (inclusive) contains (gx, gy).
        //            The +1 extent (maxX, maxY) captures south/east walls and any other
        //            exterior tiles regardless of their specific position relative to rooms.
        int[][][] tileBuilding = new int[numLevels][cellH][cellW];
        for (int[][] lv : tileBuilding) for (int[] row : lv) Arrays.fill(row, -1);

        for (int z = 0; z < numLevels; z++) {
            // Pass 1: direct room assignment via lotpack roomId.
            for (int gy = 0; gy < cellH; gy++) {
                for (int gx = 0; gx < cellW; gx++) {
                    int rid = roomIds[z][gy][gx];
                    if (rid >= 0 && rid < roomToBuilding.length && roomToBuilding[rid] >= 0)
                        tileBuilding[z][gy][gx] = roomToBuilding[rid];
                }
            }
            // Pass 2: any tile with data that falls within a building's bounding box.
            for (int gy = 0; gy < cellH; gy++) {
                for (int gx = 0; gx < cellW; gx++) {
                    if (tileBuilding[z][gy][gx] >= 0) continue;
                    boolean hasData = false;
                    for (int sl = 0; sl < NUM_SUBLAYERS && !hasData; sl++)
                        if (gy < tiles[z][sl].length && gx < tiles[z][sl][gy].length
                                && tiles[z][sl][gy][gx] >= 0) hasData = true;
                    if (!hasData) continue;
                    for (int bi = 0; bi < numBuildings; bi++) {
                        if (gx >= minX[bi] && gx <= maxX[bi]
                                && gy >= minY[bi] && gy <= maxY[bi]) {
                            tileBuilding[z][gy][gx] = bi;
                            break;
                        }
                    }
                }
            }
        }

                // Build BuildingData for each building.
        for (int bi = 0; bi < numBuildings; bi++) {
            LotBuilding bld = header.buildings.get(bi);
            if (bld.roomIDs.isEmpty()) continue;
            if (minX[bi] > maxX[bi] || minY[bi] > maxY[bi]) continue;

            int bw = maxX[bi] - minX[bi];
            int bh = maxY[bi] - minY[bi];
            if (bw <= 0 || bh <= 0) continue;

            BuildingData bd = new BuildingData();
            bd.index = bi;
            bd.ox    = minX[bi];
            bd.oy    = minY[bi];
            bd.bw    = bw;
            bd.bh    = bh;

            // Tile grid is (bh+1) x (bw+1): the extra row (by==bh) holds south-wall tiles
            // and the extra col (bx==bw) holds east-wall tiles, matching the tbx tile CSV.
            bd.buildingTiles = new int[numLevels][NUM_SUBLAYERS][bh + 1][bw + 1];
            for (int[][][] lv : bd.buildingTiles)
                for (int[][] sl : lv)
                    for (int[] row : sl)
                        Arrays.fill(row, -1);

            // Build a fast lookup: is (gx,gy) inside another building's room-rect zone?
            // Used to avoid stealing tiles that belong to an adjacent building.
            // (The bbox-vs-bbox expansion already guards against overlapping bboxes, but
            //  we double-check at tile level here.)
            // We use the ORIGINAL room-rect extents for the guard (origMinX etc. below).

            for (int z = 0; z < numLevels; z++) {
                for (int by = 0; by <= bh; by++) {
                    for (int bx = 0; bx <= bw; bx++) {
                        int gy = minY[bi] + by, gx = minX[bi] + bx;
                        if (gy < 0 || gy >= cellH || gx < 0 || gx >= cellW) continue;
                        // Skip tiles inside another building's room-rect zone
                        // (they were claimed by Pass 1 for that building).
                        if (z < roomIds.length && gy < roomIds[z].length && gx < roomIds[z][gy].length) {
                            int rid = roomIds[z][gy][gx];
                            if (rid >= 0 && rid < roomToBuilding.length) {
                                int owner = roomToBuilding[rid];
                                if (owner >= 0 && owner != bi) continue;
                            }
                        }
                        // Copy the 6 named sublayers into the building's tile store
                        // and erase them from the TMX grid (they go to the .tbx file).
                        // When --excludebuilding is active the building tiles are erased
                        // from the TMX just the same (no .tbx is written), but the lowest
                        // Floor sublayer (sl==0) is *kept* in the TMX so that ground-level
                        // floor surfaces remain visible after compaction.
                        for (int sl = 0; sl < NUM_SUBLAYERS; sl++) {
                            if (gy < tiles[z][sl].length && gx < tiles[z][sl][gy].length) {
                                bd.buildingTiles[z][sl][by][bx] = tiles[z][sl][gy][gx];
                                // In --excludebuilding mode, preserve only the ground-level
                                // Floor sublayer (z==0, sl==0) in the TMX so that the
                                // terrain/ground surface is visible after the building is
                                // removed.  Everything on upper levels (z>0) is erased
                                // entirely — those are interior floors, walls, roof tiles
                                // that have no meaning without the building structure.
                                boolean keepInTmx = excludeBuilding && z == 0 && sl == 0;
                                if (!keepInTmx) {
                                    tiles[z][sl][gy][gx] = -1;  // erase from TMX grid
                                }
                            }
                        }
                        // Overflow sublayers (slots NUM_SUBLAYERS..NUM_ALL_SUBLAYERS-1) are
                        // NOT copied to tbx and NOT erased — they stay in the TMX grid so
                        // no tile data is lost for squares that have >6 lotpack entries.
                        // Exception: when --excludebuilding is active, erase overflow slots
                        // too so no building data bleeds through on any layer.
                        if (excludeBuilding) {
                            for (int ovsl = NUM_SUBLAYERS; ovsl < NUM_ALL_SUBLAYERS; ovsl++) {
                                if (gy < tiles[z][ovsl].length && gx < tiles[z][ovsl][gy].length)
                                    tiles[z][ovsl][gy][gx] = -1;
                            }
                        }
                    }
                }
            }

            // Build room grid and room name list (for TBX <rooms> CSV).
            bd.roomNames.add("");  // index 0 = no room
            bd.roomGrid = new int[numLevels][bh][bw];
            for (int rid : bld.roomIDs) {
                LotRoom room = header.rooms.get(rid);
                int localIdx = bd.roomNames.size();  // 1-based
                bd.roomNames.add(room.name);
                for (int[] rc : room.rects) {
                    int z = Math.min(room.floor, numLevels - 1);
                    // rc[0..1] are already local cell coords; subtract building minX/Y for building-local.
                    for (int ry2 = rc[1]; ry2 < rc[1] + rc[3]; ry2++) {
                        for (int rx2 = rc[0]; rx2 < rc[0] + rc[2]; rx2++) {
                            int by = ry2 - minY[bi], bx = rx2 - minX[bi];
                            if (by >= 0 && by < bh && bx >= 0 && bx < bw)
                                bd.roomGrid[z][by][bx] = localIdx;
                        }
                    }
                }
            }

            result.add(bd);
        }
        return result;
    }

    // ── .tbx writer ──────────────────────────────────────────────────────────
    // Writes a minimal but valid TileZed .tbx file reconstructed from lotpack data.

    // ── .tbx writer ──────────────────────────────────────────────────────────
    // Writes a minimal but valid TileZed .tbx file reconstructed from lotpack data.
    // The building's raw tiles are stored as "user tile" (grime) layers because
    // we cannot recover semantic tile categories (walls, floors, doors) from
    // compiled data alone.  The room layout is preserved from the lotheader.
    //
    // .tbx XML structure (version 3, from buildingwriter.cpp):
    //   <building version="3" width="W" height="H" ExteriorWall="0" ...>
    //     <used_tiles></used_tiles>
    //     <used_furniture></used_furniture>
    //     <room Name="..." InternalName="..." Color="r g b" InteriorWall="0" Floor="0" .../>
    //     <floor>
    //       <rooms> CSV grid of 1-based room indices </rooms>
    //       <tiles layer="layerName"> CSV grid of 1-based user-tile indices </tiles>
    //     </floor>
    //     ...
    //     <user_tiles>
    //       <tile tile="tileset_N"/>
    //     </user_tiles>
    //   </building>

    // Mapping from lotpack sublayer index -> .tbx layer name (6 sublayers).
    // sublayer 0=Floor  1=Walls  2=Walls2  3=Frames  4=Furniture  5=Vegetation
    static final String[] TBX_LAYERS = {
        "Floor", "Walls", "Walls2", "Frames", "Furniture", "Vegetation"
    };

    // ── .tbx writer ──────────────────────────────────────────────────────────
    // Format confirmed from Lost_Church.tbx / buildingwriter.cpp:
    //  - Layer names are plain: "Floor", "Walls" etc. (NO floor-number prefix)
    //  - Rooms CSV:  bh rows x bw cols, trailing comma per row
    //  - Tiles CSV:  (bh+1) rows x (bw+1) cols, trailing comma per row
    //  - Element order: user_tiles > used_tiles > used_furniture > room* > floor*
    static void writeTbx(Path out, BuildingData bd, LotHeader header) throws IOException {
        int numLevels = header.numLevels;
        int bw = bd.bw, bh = bd.bh;

        // Collect all unique tile names used, in sorted order, to build user-tile list.
        Set<String> usedSet = new LinkedHashSet<>();
        for (int z = 0; z < numLevels; z++)
            for (int sl = 0; sl < NUM_SUBLAYERS; sl++)
                for (int by = 0; by <= bh; by++)
                    for (int bx = 0; bx <= bw; bx++) {
                        int tid = bd.buildingTiles[z][sl][by][bx];
                        if (tid >= 0 && tid < header.tileNames.size())
                            usedSet.add(header.tileNames.get(tid));
                    }
        List<String> userTileList = new ArrayList<>(usedSet);
        Collections.sort(userTileList);
        Map<String, Integer> tileIdx = new HashMap<>(); // 1-based index in user_tiles
        for (int i = 0; i < userTileList.size(); i++) tileIdx.put(userTileList.get(i), i + 1);

        // Highest floor level containing any tile data (to avoid emitting empty upper floors)
        int maxFloor = 0;
        outerSearch:
        for (int z = numLevels - 1; z >= 0; z--)
            for (int sl = 0; sl < NUM_SUBLAYERS; sl++)
                for (int by = 0; by <= bh; by++)
                    for (int bx = 0; bx <= bw; bx++)
                        if (bd.buildingTiles[z][sl][by][bx] >= 0) { maxFloor = z; break outerSearch; }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<building version=\"3\"");
        sb.append(" width=\"").append(bw).append("\"");
        sb.append(" height=\"").append(bh).append("\"");
        for (String attr : new String[]{
                "ExteriorWall","Door","DoorFrame","Window","Curtains","Shutters",
                "Stairs","RoofCap","RoofSlope","RoofTop","GrimeWall"})
            sb.append(" ").append(attr).append("=\"0\"");
        sb.append(">\n");

        // <user_tiles> — comes before rooms and floors (buildingwriter.cpp order)
        sb.append(" <user_tiles>\n");
        for (String tname : userTileList)
            sb.append("  <tile tile=\"").append(xmlEsc(tname)).append("\"/>\n");
        sb.append(" </user_tiles>\n");
        sb.append(" <used_tiles></used_tiles>\n");
        sb.append(" <used_furniture></used_furniture>\n");

        // <room> elements (1-based from bd.roomNames; index 0 is the null-room placeholder)
        int[][] ROOM_COLORS = {
            {200,100,100}, {100,200,100}, {100,100,200},
            {200,200,100}, {200,100,200}, {100,200,200},
            {180,140,100}, {140,100,180}, {100,180,140}
        };
        for (int ri = 1; ri < bd.roomNames.size(); ri++) {
            String rname = xmlEsc(bd.roomNames.get(ri));
            int[] col = ROOM_COLORS[(ri - 1) % ROOM_COLORS.length];
            sb.append(" <room Name=\"").append(rname).append("\"")
              .append(" InternalName=\"").append(rname).append("\"")
              .append(" Color=\"").append(col[0]).append(" ").append(col[1]).append(" ").append(col[2]).append("\"")
              .append(" InteriorWall=\"0\" Floor=\"0\" InteriorWallTrim=\"0\" ExteriorWallTrim=\"0\"")
              .append("/>\n");
        }

        // <floor> elements — one per z-level from 0 to maxFloor
        for (int z = 0; z <= maxFloor; z++) {
            sb.append(" <floor>\n");

            // <rooms> CSV: bh rows x bw cols.
            // Trailing comma on every row EXCEPT the last (buildingreader.cpp counts commas,
            // a trailing comma on the last row causes y to exceed floor->height() → "Corrupt").
            sb.append("  <rooms>\n");
            for (int by = 0; by < bh; by++) {
                for (int bx = 0; bx < bw; bx++) {
                    sb.append(bd.roomGrid[z][by][bx]);
                    if (bx < bw - 1 || by < bh - 1) sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  </rooms>\n");

            // <tiles layer="..."> for each sublayer with data
            // Grid: (bh+1) rows x (bw+1) cols — the extra row (by==bh) holds south-wall tiles
            // and the extra col (bx==bw) holds east-wall tiles.
            // Same rule: no trailing comma on the very last value.
            for (int sl = 0; sl < NUM_SUBLAYERS; sl++) {
                boolean hasData = false;
                outer:
                for (int by = 0; by <= bh && !hasData; by++)
                    for (int bx = 0; bx <= bw && !hasData; bx++)
                        if (bd.buildingTiles[z][sl][by][bx] >= 0) { hasData = true; break outer; }
                if (!hasData) continue;

                sb.append("  <tiles layer=\"").append(TBX_LAYERS[sl]).append("\">\n");
                int tRows = bh + 1, tCols = bw + 1;
                for (int by = 0; by <= bh; by++) {
                    for (int bx = 0; bx <= bw; bx++) {
                        int tid = bd.buildingTiles[z][sl][by][bx];
                        if (tid >= 0 && tid < header.tileNames.size()) {
                            sb.append(tileIdx.getOrDefault(header.tileNames.get(tid), 0));
                        } else {
                            sb.append("0");
                        }
                        if (bx < tCols - 1 || by < tRows - 1) sb.append(",");
                    }
                    sb.append("\n");
                }
                sb.append("  </tiles>\n");
            }

            sb.append(" </floor>\n");
        }

        sb.append("</building>\n");
        Files.writeString(out, sb, StandardCharsets.UTF_8);
    }


    // ── PZW writer ────────────────────────────────────────────────────────────

    /**
     * Detect the ProjectZomboid/media folder inside a Steam installation.
     * Returns null if not found; callers should warn and fall back to a dummy path.
     */
    // ── Steam detection ───────────────────────────────────────────────────────

    /**
     * Lightweight parser for Valve's KeyValues / VDF text format.
     * Only handles the flat "key" "value" and nested "key" { ... } forms
     * used in libraryfolders.vdf — no dependency on any external library.
     */
    static java.util.Map<String, String> parseVdfValues(String vdf) {
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        // Match   "key"   "value"  pairs (the flat entries we care about).
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"([^\"]+)\"\\s+\"([^\"]+)\"")
            .matcher(vdf);
        while (m.find())
            values.put(m.group(1), m.group(2));
        return values;
    }

    /**
     * Extract all Steam library root paths from a libraryfolders.vdf file.
     * Each numbered block (0, 1, 2 …) contains a "path" key.
     */
    static List<Path> parseSteamLibraries(Path vdfPath) {
        List<Path> libs = new ArrayList<>();
        try {
            String vdf = Files.readString(vdfPath, java.nio.charset.StandardCharsets.UTF_8);
            // Split on top-level numbered blocks: "0" { ... }
            java.util.regex.Matcher blocks = java.util.regex.Pattern
                .compile("\"\\d+\"\\s*\\{([^}]+)\\}")
                .matcher(vdf);
            while (blocks.find()) {
                java.util.Map<String, String> kv = parseVdfValues(blocks.group(1));
                String pathStr = kv.get("path");
                if (pathStr != null) {
                    Path p = Path.of(pathStr);
                    if (Files.isDirectory(p)) libs.add(p);
                }
            }
        } catch (Exception ignored) {}
        return libs;
    }

    /**
     * Use the Windows registry to find where Steam itself is installed.
     * Mirrors the logic in Decompile.java's findSteamPath().
     * Returns null on non-Windows or if the registry query fails.
     */
    static Path findSteamRootWindows() {
        try {
            // 64-bit Windows stores the Steam key under Wow6432Node; 32-bit does not.
            String regKey = System.getProperty("os.arch", "").contains("64")
                ? "HKEY_LOCAL_MACHINE\\SOFTWARE\\Wow6432Node\\Valve\\Steam"
                : "HKEY_LOCAL_MACHINE\\SOFTWARE\\Valve\\Steam";

            Process proc = Runtime.getRuntime().exec(
                new String[]{"reg", "query", regKey, "/v", "InstallPath"});
            proc.waitFor();
            String out = new String(proc.getInputStream().readAllBytes()).trim();

            // Output looks like:   InstallPath    REG_SZ    C:\Program Files (x86)\Steam
            // The actual path is everything after the last run of 4+ spaces.
            int idx = out.lastIndexOf("    ");
            if (idx < 0) return null;
            Path p = Path.of(out.substring(idx).trim());
            return Files.isDirectory(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Collect all Steam library roots on this machine.
     * On Windows: uses the registry to find the Steam install, then reads
     * libraryfolders.vdf to discover every additional library.
     * On Linux/macOS: checks the well-known default Steam data directories.
     */
    static List<Path> findAllSteamLibraries() {
        List<Path> steamRoots = new ArrayList<>();
        String os  = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");

        if (os.contains("win")) {
            Path steamRoot = findSteamRootWindows();
            if (steamRoot != null) steamRoots.add(steamRoot);
            // Also check common fallback locations in case registry query failed.
            for (String fb : new String[]{
                    System.getenv("ProgramFiles(x86)") != null
                        ? System.getenv("ProgramFiles(x86)") + "\\Steam" : null,
                    "C:\\Program Files (x86)\\Steam",
                    "C:\\Program Files\\Steam"}) {
                if (fb != null) { Path p = Path.of(fb); if (Files.isDirectory(p)) steamRoots.add(p); }
            }
        } else if (os.contains("mac")) {
            for (String r : new String[]{
                    home + "/Library/Application Support/Steam"})
                { Path p = Path.of(r); if (Files.isDirectory(p)) steamRoots.add(p); }
        } else {
            // Linux / Steam Deck
            for (String r : new String[]{
                    home + "/.steam/steam",
                    home + "/.local/share/Steam",
                    home + "/snap/steam/common/.local/share/Steam"})
                { Path p = Path.of(r); if (Files.isDirectory(p)) steamRoots.add(p); }
        }

        // Expand each Steam root via its libraryfolders.vdf to find extra libraries.
        List<Path> allLibs = new ArrayList<>(steamRoots);
        for (Path root : steamRoots) {
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            if (Files.isRegularFile(vdf))
                for (Path lib : parseSteamLibraries(vdf))
                    if (!allLibs.contains(lib)) allLibs.add(lib);
        }
        return allLibs;
    }

    /**
     * Search every detected Steam library for a ProjectZomboid/media folder.
     * Returns the path as a String, or null if not found.
     * On macOS the media folder is inside the .app bundle.
     */
    static String detectSteamMediaPath() {
        String os = System.getProperty("os.name", "").toLowerCase();

        for (Path lib : findAllSteamLibraries()) {
            // Standard location across Windows and Linux.
            Path pz = lib.resolve("steamapps/common/ProjectZomboid");
            Path media = pz.resolve("media");
            if (Files.isDirectory(media)) return media.toString();

            // macOS wraps the game in an .app bundle.
            if (os.contains("mac")) {
                Path macMedia = pz.resolve(
                    "ProjectZomboid.app/Contents/Resources/media");
                if (Files.isDirectory(macMedia)) return macMedia.toString();
            }
        }
        return null;
    }

    /**
     * Write a WorldEd project file (.pzw) that links all processed cells.
     *
     * @param pzwPath       destination path for the .pzw file
     * @param pzwName       base name (no extension) used in ZombieSpawnMap filename
     * @param cells         list of [cellX, cellY] pairs that were successfully processed
     * @param minCX,minCY   smallest cell X and Y across all discovered lotheaders
     * @param maxCX,maxCY   largest  cell X and Y
     * @param outDir        output directory (maps/ subfolder created here)
     */
    // ── objects.lua zone support ─────────────────────────────────────────────

    /** A single zone entry parsed from objects.lua. */
    static class LuaZone {
        String type;       // e.g. "TownZone", "Nav", "Forest"
        String name;       // may be empty
        int    level;      // z-level (almost always 0)
        // Geometry: always stored as a flat list of world-coord tile points.
        // For rect objects: 4 points representing the corners.
        // For polygon/polyline: the actual polygon vertices.
        List<long[]> points; // each element is {worldX, worldY}
        boolean isRect;    // true if originated from x/y/w/h rect form
    }

    /**
     * Parse an objects.lua file as produced by WorldEd's LuaWriter::writeWorldObjects().
     *
     * The file is a Lua table:
     *   objects = {
     *     {name="", type="TownZone", x=WX, y=WY, z=0, width=W, height=H},
     *     {name="", type="Nav",      z=0, geometry="polygon", points={x1,y1,x2,y2,...}},
     *   }
     *
     * We do a best-effort parse using regex rather than a full Lua interpreter,
     * which is sufficient for the well-structured output that WorldEd produces.
     */
    static List<LuaZone> parseLuaObjects(String lua) {
        List<LuaZone> zones = new ArrayList<>();

        // Strip comments and find the top-level "objects = { ... }" table.
        // WorldEd writes the table at the top level (no function wrapper in writeWorldObjects).
        // Each entry is one table on its own line(s): { key=val, ... }
        // We find each { ... } block (handling nested braces for the points table).
        // First, locate where the entries begin (skip "objects = {" header line).
        int start = lua.indexOf('{');
        if (start < 0) return zones;
        // Skip the outer opening brace
        start++;
        int end = lua.lastIndexOf('}');
        if (end <= start) return zones;
        String body = lua.substring(start, end);

        // Split into individual zone entry blocks by finding balanced { } pairs.
        List<String> entries = splitLuaTableEntries(body);

        for (String entry : entries) {
            entry = entry.trim();
            if (entry.isEmpty() || entry.equals(",")) continue;
            // Remove leading/trailing braces and commas
            // Strip exactly one outermost { } pair, plus leading/trailing commas.
            if (entry.startsWith("{")) entry = entry.substring(1);
            if (entry.endsWith("}"))   entry = entry.substring(0, entry.length() - 1);
            entry = entry.strip().replaceAll("^,|,$", "").strip();
            if (entry.isEmpty()) continue;

            LuaZone z = new LuaZone();
            z.name   = luaStringField(entry, "name", "");
            z.type   = luaStringField(entry, "type", "");
            z.level  = (int) luaNumberField(entry, "z",     0);
            if (z.type.isEmpty()) continue;

            // Check for geometry type
            String geom = luaStringField(entry, "geometry", "");
            if (!geom.isEmpty()) {
                // Polygon / polyline form: points = {x1,y1,x2,y2,...}
                z.isRect = false;
                z.points = parseLuaPoints(entry);
            } else {
                // Rect form: x, y, width, height (all in world tile coordinates)
                long wx = (long) luaNumberField(entry, "x", 0);
                long wy = (long) luaNumberField(entry, "y", 0);
                long ww = (long) luaNumberField(entry, "width",  0);
                long wh = (long) luaNumberField(entry, "height", 0);
                z.isRect = true;
                // Store as 4 corner points (clockwise from top-left)
                z.points = List.of(
                    new long[]{wx,      wy     },
                    new long[]{wx + ww, wy     },
                    new long[]{wx + ww, wy + wh},
                    new long[]{wx,      wy + wh}
                );
            }
            if (!z.points.isEmpty()) zones.add(z);
        }
        return zones;
    }

    /** Split a Lua table body into individual entry strings, respecting nested braces. */
    static List<String> splitLuaTableEntries(String body) {
        List<String> entries = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    entries.add(body.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return entries;
    }

    /** Extract a string value from a Lua table entry: key = "value" or key = 'value' */
    static String luaStringField(String entry, String key, String def) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*[\"']([^\"']*)[\"']");
        Matcher m = p.matcher(entry);
        return m.find() ? m.group(1) : def;
    }

    /** Extract a numeric value from a Lua table entry: key = number */
    static double luaNumberField(String entry, String key, double def) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(key) + "\\s*=\\s*(-?[\\d.]+)");
        Matcher m = p.matcher(entry);
        if (m.find()) { try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) {} }
        return def;
    }

    /**
     * Parse the points sub-table from a polygon entry:
     *   points = {x1, y1, x2, y2, ...}
     * Returns list of [worldX, worldY] pairs.
     */
    static List<long[]> parseLuaPoints(String entry) {
        List<long[]> pts = new ArrayList<>();
        // Find "points = { ... }"
        int ps = entry.indexOf("points");
        if (ps < 0) return pts;
        int pb = entry.indexOf('{', ps);
        if (pb < 0) return pts;
        int pe = entry.lastIndexOf('}');
        if (pe <= pb) return pts;
        String inner = entry.substring(pb + 1, pe);
        // Split by comma and parse alternating x,y pairs
        String[] nums = inner.split("[,\\s]+");
        List<Long> vals = new ArrayList<>();
        for (String n : nums) {
            n = n.trim();
            if (n.isEmpty()) continue;
            try { vals.add((long) Double.parseDouble(n)); } catch (NumberFormatException e) {}
        }
        for (int i = 0; i + 1 < vals.size(); i += 2)
            pts.add(new long[]{vals.get(i), vals.get(i + 1)});
        return pts;
    }

    /**
     * Assign each zone to the cell it overlaps the most (by tile area).
     *
     * Strategy:
     *   1. Compute the world-coordinate bounding box of the zone's points.
     *   2. For each cell the bbox overlaps, compute the overlap area of the zone's
     *      BBOX with that cell's 300×300 tile area.
     *   3. Assign the zone to the cell with the largest overlap.
     *   4. Convert the zone's world coordinates to cell-local [0,300) coords.
     *   5. Emit a PZW <object> XML line for that cell.
     *
     * Returns a map from "cellX_cellY" → list of <object ...> lines.
     *
     * @param luaZones   zones from objects.lua (world-coordinate points)
     * @param minCX/maxCX/minCY/maxCY  known cell extent (all cells in project)
     */
    static Map<String, List<String>> assignZonesToCells(
            List<LuaZone> luaZones, int minCX, int minCY, int maxCX, int maxCY) {

        Map<String, List<String>> result = new LinkedHashMap<>();
        int skipped = 0;

        for (LuaZone z : luaZones) {
            if (z.points.isEmpty()) { skipped++; continue; }

            // Compute world-coordinate bounding box
            long minWX = Long.MAX_VALUE, maxWX = Long.MIN_VALUE;
            long minWY = Long.MAX_VALUE, maxWY = Long.MIN_VALUE;
            for (long[] pt : z.points) {
                minWX = Math.min(minWX, pt[0]); maxWX = Math.max(maxWX, pt[0]);
                minWY = Math.min(minWY, pt[1]); maxWY = Math.max(maxWY, pt[1]);
            }

            // Find the cell with maximum bbox overlap
            int bestCX = -1, bestCY = -1;
            long bestArea = -1;
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cy = minCY; cy <= maxCY; cy++) {
                    long cellMinWX = (long) cx * 300;
                    long cellMinWY = (long) cy * 300;
                    long cellMaxWX = cellMinWX + 300;
                    long cellMaxWY = cellMinWY + 300;
                    long ox = Math.max(0, Math.min(maxWX, cellMaxWX) - Math.max(minWX, cellMinWX));
                    long oy = Math.max(0, Math.min(maxWY, cellMaxWY) - Math.max(minWY, cellMinWY));
                    long area = ox * oy;
                    if (area > bestArea) {
                        bestArea = area;
                        bestCX = cx;
                        bestCY = cy;
                    }
                }
            }
            if (bestCX < 0 || bestArea <= 0) { skipped++; continue; }

            // Convert world coords to cell-local coords
            long originX = (long) bestCX * 300;
            long originY = (long) bestCY * 300;

            // Build the "points" string: "x1,y1 x2,y2 ..."
            StringBuilder pts = new StringBuilder();
            for (int i = 0; i < z.points.size(); i++) {
                if (i > 0) pts.append(' ');
                long lx = z.points.get(i)[0] - originX;
                long ly = z.points.get(i)[1] - originY;
                pts.append(lx).append(',').append(ly);
            }

            String typeEsc = xmlEsc(z.type);
            String line = "  <object group=\"" + typeEsc + "\" type=\"" + typeEsc
                        + "\" level=\"" + z.level + "\" geometry=\"polygon\""
                        + " points=\"" + pts + "\"/>\n";

            String cellKey = bestCX + "_" + bestCY;
            result.computeIfAbsent(cellKey, k -> new ArrayList<>()).add(line);
        }

        if (skipped > 0)
            System.err.println("WARNING: " + skipped + " zone(s) could not be assigned to any cell — skipped.");
        return result;
    }

    /**
     * Build the <objecttype> and <objectgroup> XML block for the PZW.
     *
     * The known types have curated colors; any extra types found in the
     * imported zones that are not already in the known set are appended
     * with no color attribute (WorldEd accepts this fine).
     */
    /**
     * Reconstruct the ZombieSpawnMap PNG from the 900-byte spawn grids embedded in
     * each cell's lotheader.
     *
     * Format (from generateHeaderAux in lotfilesmanager.cpp):
     *   for x in [0,30): for y in [0,30):
     *     byte = qRed(ZombieSpawnMap.pixel(cellX*30+x, cellY*30+y))
     *
     * So each byte is the RED channel of one pixel.  The original PNG is grayscale
     * (R=G=B=value).  Black (0) = no zombie spawn; white (255) = full spawn density.
     *
     * PNG dimensions: (maxCX-minCX+1)*30 × (maxCY-minCY+1)*30 pixels.
     * Each pixel covers one 10×10-tile chunk.
     * Cells not present in spawnDataMap are left black (all zeros).
     *
     * @param out         destination PNG path
     * @param spawnDataMap  "cellX_cellY" → 900 bytes, x-outer y-inner
     * @param minCX/minCY/maxCX/maxCY  world cell extent
     */
    static void writeZombieSpawnMap(Path out,
                                    Map<String, byte[]> spawnDataMap,
                                    int minCX, int minCY,
                                    int maxCX, int maxCY) throws IOException {
        int cellsW = maxCX - minCX + 1;
        int cellsH = maxCY - minCY + 1;
        int imgW   = cellsW * 30;
        int imgH   = cellsH * 30;

        // RGB image: grayscale gradient — R=G=B=value (0=black, 255=white).
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        // BufferedImage is zero-initialised → all pixels start black (0x000000). Good.

        for (Map.Entry<String, byte[]> e : spawnDataMap.entrySet()) {
            String key = e.getKey();
            byte[] spawn = e.getValue();
            if (spawn.length < 900) continue;

            int[] cc = parseCellCoords(key + ".lotheader"); // reuse parser
            int cx = cc[0];
            int cy = cc[1];

            // Pixel origin for this cell in the merged image
            int baseX = (cx - minCX) * 30;
            int baseY = (cy - minCY) * 30;

            // Data is stored x-outer y-inner: spawn[x*30 + y]
            for (int x = 0; x < 30; x++) {
                for (int y = 0; y < 30; y++) {
                    int v = spawn[x * 30 + y] & 0xFF; // unsigned
                    int rgb = (v << 16) | (v << 8) | v; // R=G=B=v (grayscale gradient)
                    img.setRGB(baseX + x, baseY + y, rgb);
                }
            }
        }

        Files.createDirectories(out.getParent() != null ? out.getParent() : Path.of("."));
        ImageIO.write(img, "png", out.toFile());
        log("  Written " + out + "  (" + imgW + "×" + imgH + " px, "
            + spawnDataMap.size() + " cell(s) merged)");
    }

    static String buildObjectTypesAndGroups(List<LuaZone> luaZones) {
        // Ordered known types: name → color (null = no color attribute)
        // Order matches the zone_example.pzw reference.
        String[][] KNOWN = {
            {"TownZone",    "#aa0000"},
            {"Forest",      "#00aa00"},
            {"DeepForest",  "#003500"},
            {"Nav",         "#55aaff"},
            {"Vegitation",  "#b3b300"},
            {"TrailerPark", "#f50000"},
            {"Farm",        "#55ff7f"},
            {"ParkingStall","#ff007f"},
            {"FarmLand",    "#bcff7d"},
            {"WaterFlow",   "#0000ff"},
            {"WaterZone",   "#0000ff"},
            {"Mannequin",   "#0000ff"},
            {"RoomTone",    "#0000ff"},
            {"SpawnPoint",  null},
            {"ZombiesType", "#ffffff"},
            {"ZoneStory",   "#8080ff"},
            {"LootZone",    "#80ff80"},
            {"WorldGen",    "#ffaa00"},
            {"ForagingNav", "#66ddff"},
            {"Foraging_None","#000000"},
            {"Water",       "#0006ff"},
            {"WaterNoFish", "#6ad600"},
            {"Ranch",       "#ff8000"},
            {"Animal",      null},
            {"Basement",    null},
        };

        // Build ordered set of known names for fast lookup.
        LinkedHashMap<String, String> types = new LinkedHashMap<>();
        for (String[] kv : KNOWN) types.put(kv[0], kv[1]);

        // Collect any extra types from zones not already in the known set,
        // preserving first-seen order.
        for (LuaZone z : luaZones) {
            if (z.type != null && !z.type.isEmpty() && !types.containsKey(z.type))
                types.put(z.type, null); // no color for unknown types
        }

        StringBuilder sb = new StringBuilder();
        // <objecttype> block
        for (String name : types.keySet())
            sb.append(" <objecttype name=\"" + xmlEsc(name) + "\"/>\n");
        // <objectgroup> block
        for (Map.Entry<String, String> e : types.entrySet()) {
            String eName = xmlEsc(e.getKey());
            String grp = " <objectgroup name=\"" + eName + "\"";
            if (e.getValue() != null) grp += " color=\"" + e.getValue() + "\"";
            grp += " defaulttype=\"" + eName + "\"/>\n";
            sb.append(grp);
        }
        return sb.toString();
    }

        static void writePzw(Path pzwPath, String pzwName,
                         List<int[]> cells,
                         int minCX, int minCY, int maxCX, int maxCY,
                         Path outDir,
                         List<LuaZone> luaZones) throws IOException {

        // World grid dimensions (number of cells wide / tall).
        int worldW = maxCX - minCX + 1;
        int worldH = maxCY - minCY + 1;

        // Create the empty maps/ subfolder that GenerateLots writes into.
        Path mapsDir = outDir.resolve("maps");
        Files.createDirectories(mapsDir);

        // Detect Steam media path.
        String tileDefPath = detectSteamMediaPath();
        if (tileDefPath == null) {
            tileDefPath = "ProjectZomboid/media";
            System.err.println("WARNING: Steam ProjectZomboid installation not found.");
            System.err.println("         TileDefFolder set to dummy path \"" + tileDefPath + "\".");
            System.err.println("         Open " + pzwPath.getFileName()
                + " in WorldEd and correct it under GenerateLots > TileDefFolder.");
        }

        // Assign each zone to the cell it overlaps most.
        // key = "cellX_cellY", value = list of <object ...> XML lines for that cell
        Map<String, List<String>> zonesByCell = assignZonesToCells(luaZones, minCX, minCY, maxCX, maxCY);

        // Build <cell> entries — normalise world coords so top-left = (0,0).
        StringBuilder cellLines = new StringBuilder();
        // Sort cells x-major then y for a tidy file.
        cells.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        for (int[] cc : cells) {
            int nx = cc[0] - minCX;   // normalised grid x (0-based)
            int ny = cc[1] - minCY;   // normalised grid y (0-based)
            String tmxName = "MyMap_" + cc[0] + "_" + cc[1] + ".tmx";
            String cellKey = cc[0] + "_" + cc[1];
            List<String> zoneLines = zonesByCell.getOrDefault(cellKey, List.of());
            if (zoneLines.isEmpty()) {
                cellLines.append(" <cell x=\"").append(nx)
                         .append("\" y=\"").append(ny)
                         .append("\" map=\"").append(tmxName).append("\"/>\n");
            } else {
                cellLines.append(" <cell x=\"").append(nx)
                         .append("\" y=\"").append(ny)
                         .append("\" map=\"").append(tmxName).append("\">\n");
                for (String zl : zoneLines) cellLines.append(zl);
                cellLines.append(" </cell>\n");
            }
        }

        // Assemble the full PZW XML, modelled on the 1x2.pzw sample.
        // The static boilerplate (propertyenum/def, templates, objecttype/group) is
        // identical across all WorldEd projects and copied verbatim from the sample.
        String pzw =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          + "<world version=\"1.0\" width=\"" + worldW + "\" height=\"" + worldH + "\">\n"
          + " <BMPToTMX>\n"
          + "  <tmxexportdir path=\".\"/>\n"
          + "  <rulesfile path=\"\"/>\n"
          + "  <blendsfile path=\"\"/>\n"
          + "  <mapbasefile path=\"\"/>\n"
          + "  <assign-maps-to-world checked=\"true\"/>\n"
          + "  <warn-unknown-colors checked=\"true\"/>\n"
          + "  <compress checked=\"true\"/>\n"
          + "  <copy-pixels checked=\"true\"/>\n"
          + "  <update-existing checked=\"false\"/>\n"
          + " </BMPToTMX>\n"
          + " <TMXToBMP>\n"
          + "  <mainImage generate=\"true\"/>\n"
          + "  <vegetationImage generate=\"true\"/>\n"
          + "  <buildingsImage path=\"\" generate=\"false\"/>\n"
          + " </TMXToBMP>\n"
          + " <GenerateLots>\n"
          + "  <exportdir path=\"maps\"/>\n"
          + "  <ZombieSpawnMap path=\"" + pzwName + "_ZombieSpawnMap.png\"/>\n"
          + "  <TileDefFolder path=\"" + tileDefPath.replace("\\", "/") + "\"/>\n"
          + "  <worldOrigin origin=\"" + minCX + "," + minCY + "\"/>\n"
          + " </GenerateLots>\n"
          + " <LuaSettings>\n"
          + "  <spawnPointsFile path=\"\"/>\n"
          + "  <worldObjectsFile path=\"\"/>\n"
          + " </LuaSettings>\n"
          + " <propertyenum name=\"Direction\" choices=\"N,S,W,E\" multi=\"false\"/>\n"
          + " <propertyenum name=\"Pose\" choices=\"pose01,pose02,pose03\" multi=\"false\"/>\n"
          + " <propertyenum name=\"Skin\" choices=\"White,Black\" multi=\"false\"/>\n"
          + " <propertyenum name=\"RoomTone\" choices=\"Generic,Barn,Mall,Warehouse,Prison,Church,Office,Factory\" multi=\"false\"/>\n"
          + " <propertydef name=\"Direction\" default=\"N\" enum=\"Direction\"/>\n"
          + " <propertydef name=\"FaceDirection\" default=\"true\"/>\n"
          + " <propertydef name=\"WaterDirection\" default=\"0.0\"/>\n"
          + " <propertydef name=\"WaterSpeed\" default=\"0.0\"/>\n"
          + " <propertydef name=\"WaterGround\" default=\"false\"/>\n"
          + " <propertydef name=\"WaterShore\" default=\"true\"/>\n"
          + " <propertydef name=\"Female\" default=\"true\"/>\n"
          + " <propertydef name=\"Outfit\" default=\"\"/>\n"
          + " <propertydef name=\"Pose\" default=\"pose01\" enum=\"Pose\"/>\n"
          + " <propertydef name=\"Script\" default=\"\"/>\n"
          + " <propertydef name=\"Skin\" default=\"White\" enum=\"Skin\"/>\n"
          + " <propertydef name=\"RoomTone\" default=\"Generic\" enum=\"RoomTone\"/>\n"
          + " <propertydef name=\"EntireBuilding\" default=\"false\"/>\n"
          + " <template name=\"ParkingStallN\">\n"
          + "  <property name=\"Direction\" value=\"N\"/>\n"
          + " </template>\n"
          + " <template name=\"ParkingStallS\">\n"
          + "  <property name=\"Direction\" value=\"S\"/>\n"
          + " </template>\n"
          + " <template name=\"ParkingStallW\">\n"
          + "  <property name=\"Direction\" value=\"W\"/>\n"
          + " </template>\n"
          + " <template name=\"ParkingStallE\">\n"
          + "  <property name=\"Direction\" value=\"E\"/>\n"
          + " </template>\n"
          + " <template name=\"WaterFlowN\">\n"
          + "  <property name=\"WaterDirection\" value=\"0\"/>\n"
          + "  <property name=\"WaterSpeed\" value=\"1.0\"/>\n"
          + " </template>\n"
          + " <template name=\"WaterFlowS\">\n"
          + "  <property name=\"WaterDirection\" value=\"180\"/>\n"
          + "  <property name=\"WaterSpeed\" value=\"1.0\"/>\n"
          + " </template>\n"
          + " <template name=\"WaterFlowE\">\n"
          + "  <property name=\"WaterDirection\" value=\"90\"/>\n"
          + "  <property name=\"WaterSpeed\" value=\"1.0\"/>\n"
          + " </template>\n"
          + " <template name=\"WaterFlowW\">\n"
          + "  <property name=\"WaterDirection\" value=\"270\"/>\n"
          + "  <property name=\"WaterSpeed\" value=\"1.0\"/>\n"
          + " </template>\n"
          + " <template name=\"WaterZone\">\n"
          + "  <property name=\"WaterGround\" value=\"false\"/>\n"
          + "  <property name=\"WaterShore\" value=\"true\"/>\n"
          + " </template>\n"
          + " <template name=\"RoomTone\">\n"
          + "  <property name=\"RoomTone\" value=\"Generic\"/>\n"
          + "  <property name=\"EntireBuilding\" value=\"false\"/>\n"
          + " </template>\n"
          + buildObjectTypesAndGroups(luaZones)
          + cellLines
          + "</world>\n";

        Files.writeString(pzwPath, pzw, java.nio.charset.StandardCharsets.UTF_8);
        log("  Written " + pzwPath);
    }

    // ── TMX writer ────────────────────────────────────────────────────────────

    static void writeTmx(Path out, LotHeader header, int[][][][] tiles,
                         Map<String, TilesetInfo> tilesetRegistry,
                         Map<String, TilesetInfo> tilesDirRegistry,
                         List<String> lotEntries,
                         boolean excludeBuilding) throws IOException {

        int cellW = header.chunksX() * header.chunkW;
        int cellH = header.chunksY() * header.chunkH;

        // ── Build tileset → firstGid mapping ─────────────────────────────────
        //
        // Two modes:
        //   A) Registry was loaded from a reference TMX (--tilesets): every TilesetInfo
        //      already has a firstGid baked in.  Use those values directly — this ensures
        //      our GIDs are bit-for-bit identical to the existing project files.
        //   B) Registry was built from a Tiles folder scan (--tilesdir): firstGid==0,
        //      so we auto-assign sequentially (alphabetical order, same as WorldEd).

        // Collect used tileset names from this cell
        Set<String> usedNames = new LinkedHashSet<>();
        for (String tn : header.tileNames) {
            if (tn != null && !tn.isEmpty()) usedNames.add(splitTileName(tn)[0]);
        }

        // Determine whether the registry has pre-set firstGids (mode A)
        boolean hasPresetGids = tilesetRegistry.values().stream()
                                    .anyMatch(t -> t.firstGid() > 0);

        Map<String, Integer> allFirstGids = new LinkedHashMap<>();
        if (hasPresetGids) {
            // Mode A: use firstGids from the reference TMX directly
            for (Map.Entry<String, TilesetInfo> e : tilesetRegistry.entrySet()) {
                allFirstGids.put(e.getKey(), e.getValue().firstGid());
            }
            // Any tile name in the lotheader not found in reference is an error/mod tile
            for (String name : usedNames) {
                if (!allFirstGids.containsKey(name)) {
                    // Append after last known tileset with a 1024-tile block
                    int maxGid = allFirstGids.values().stream().mapToInt(i->i).max().orElse(0);
                    // Find the last entry to compute next available slot
                    int lastEnd = 1;
                    for (Map.Entry<String, TilesetInfo> e : tilesetRegistry.entrySet()) {
                        int end = e.getValue().firstGid() + e.getValue().tileCount();
                        if (end > lastEnd) lastEnd = end;
                    }
                    log("      WARNING: tileset '" + name + "' not in reference TMX,"
                      + " assigning GID " + lastEnd + " (block size 1024)");
                    allFirstGids.put(name, lastEnd);
                    // Resolve source path: prefer tilesDir scan (knows subfolder like "2x/")
                    // over the hardcoded flat "../Tiles/{name}.png" fallback.
                    TilesetInfo fallback = tilesDirRegistry.get(name);
                    if (fallback != null) {
                        tilesetRegistry.put(name, new TilesetInfo(name, fallback.filename(),
                            fallback.sourcePath(), fallback.imgW(), fallback.imgH(), lastEnd));
                    } else {
                        tilesetRegistry.put(name, new TilesetInfo(name, name + ".png", "../Tiles/" + name + ".png", 1024, 2048, lastEnd));
                    }
                }
            }
        } else {
            // Mode B: auto-assign sequentially from lotheader tile names only.
            // Compute each tileset's block size from the maximum local tile index
            // seen in this cell's tile list. Standard PZ tilesets are 16 tiles wide
            // (1024px / 64px per tile); block size is rounded up to the next multiple of 16.
            // This produces a self-consistent GID space without needing the Tiles folder
            // or a reference TMX.
            final int TILESET_COLS = 16;
            Map<String, Integer> maxLocalIdx = new LinkedHashMap<>();
            for (String tn : header.tileNames) {
                if (tn == null || tn.isEmpty()) continue;
                String[] parts = splitTileName(tn);
                int localId = Integer.parseInt(parts[1]);
                maxLocalIdx.merge(parts[0], localId, Math::max);
            }
            // Sort tileset names alphabetically (WorldEd order)
            List<String> sortedNames = new ArrayList<>(maxLocalIdx.keySet());
            Collections.sort(sortedNames);
            int nextGid = 1;
            for (String name : sortedNames) {
                allFirstGids.put(name, nextGid);
                int maxLocal = maxLocalIdx.get(name);
                int rows = (maxLocal / TILESET_COLS) + 1;   // rows needed to include maxLocal
                int blockSize = rows * TILESET_COLS;         // always a multiple of 16

                // If we have a real scanned TilesetInfo (from --tilesdir), use its actual
                // dims and path. Only the block size (for GID spacing) comes from the
                // lotheader max-index calculation — the scanned dims are authoritative.
                // If no scan entry exists, synthesise a placeholder from the computed rows.
                if (!tilesetRegistry.containsKey(name)) {
                    int imgW = TILESET_COLS * TILE_IMG_W;    // = 16 * 64 = 1024
                    int imgH = rows * TILE_IMG_H;            // = rows * 128
                    tilesetRegistry.put(name, new TilesetInfo(name, name + ".png", imgW, imgH));
                }
                // Use actual tileCount from scanned info for blockSize when available,
                // so GID spacing matches the real sheet dimensions.
                TilesetInfo scannedInfo = tilesetRegistry.get(name);
                if (scannedInfo != null && scannedInfo.tileCount() > blockSize) {
                    blockSize = scannedInfo.tileCount();
                }
                nextGid += blockSize;
            }
            // Tilesets in registry (from --tilesdir scan) that aren't used in this cell:
            // still assign them GIDs so the full registry is emitted in correct order.
            for (Map.Entry<String, TilesetInfo> e : tilesetRegistry.entrySet()) {
                if (!allFirstGids.containsKey(e.getKey())) {
                    allFirstGids.put(e.getKey(), nextGid);
                    nextGid += e.getValue().tileCount();
                }
            }
        }

        // Build tile-name → GID lookup
        Map<String, Integer> gidMap = new HashMap<>();
        for (String tn : header.tileNames) {
            if (tn == null || tn.isEmpty()) continue;
            String[] parts = splitTileName(tn);
            Integer first  = allFirstGids.get(parts[0]);
            if (first != null)
                gidMap.put(tn, first + Integer.parseInt(parts[1]));
        }

        // ── Build XML ─────────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder(4 * 1024 * 1024);

        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<map version=\"1.0\" orientation=\"levelisometric\"");
        sb.append(" width=\"300\" height=\"300\"");
        sb.append(" tilewidth=\"").append(MAP_TILE_W).append("\"");
        sb.append(" tileheight=\"").append(MAP_TILE_H).append("\">\n");

        // <tileset> — emit ALL tilesets from the registry in alphabetical order so that
        // every GID is consistent with the full Tiles folder. WorldEd requires the
        // complete list to resolve GIDs correctly.
        for (Map.Entry<String, TilesetInfo> entry : tilesetRegistry.entrySet()) {
            String name      = entry.getKey();
            TilesetInfo info = entry.getValue();
            int firstGid     = allFirstGids.get(name);

            sb.append(" <tileset firstgid=\"").append(firstGid).append("\"");
            sb.append(" name=\"").append(xmlEsc(name)).append("\"");
            sb.append(" tilewidth=\"").append(TILE_IMG_W).append("\"");
            sb.append(" tileheight=\"").append(TILE_IMG_H).append("\">\n");

            if (info != null) {
                // If tilesDirRegistry has an entry for this tileset, use its actual
                // scanned dims (correct) instead of whatever the reference TMX says.
                // This fixes cases where the reference TMX was produced with wrong
                // width/height values (e.g. wrong dims for JPEG-encoded .png tilesets).
                // Source path still comes from the reference TMX so GIDs are preserved.
                TilesetInfo scanned = tilesDirRegistry.get(name);
                int emitW = (scanned != null) ? scanned.imgW() : info.imgW();
                int emitH = (scanned != null) ? scanned.imgH() : info.imgH();
                String emitSrc = (scanned != null) ? scanned.sourcePath() : info.sourcePath();
                sb.append("  <image source=\"").append(xmlEsc(emitSrc)).append("\"");
                sb.append(" width=\"").append(emitW).append("\"");
                sb.append(" height=\"").append(emitH).append("\"/>\n");
            } else {
                // No info from primary registry — try tilesDir for correct subfolder path
                TilesetInfo td = tilesDirRegistry.get(name);
                String src = (td != null) ? td.sourcePath() : "../Tiles/" + name + ".png";
                String emitSrcFb = src;
                if (td != null) {
                    sb.append("  <image source=\"").append(xmlEsc(emitSrcFb)).append("\"");
                    sb.append(" width=\"").append(td.imgW()).append("\"");
                    sb.append(" height=\"").append(td.imgH()).append("\"/>\n");
                } else {
                    sb.append("  <image source=\"").append(xmlEsc(emitSrcFb)).append("\"/>\n");
                }
            }
            sb.append(" </tileset>\n");
        }
        // Emit any modded tilesets referenced by lotheader but absent from Tiles folder
        for (String name : usedNames) {
            if (!tilesetRegistry.containsKey(name)) {
                int firstGid = allFirstGids.get(name);
                sb.append(" <tileset firstgid=\"").append(firstGid).append("\"");
                sb.append(" name=\"").append(xmlEsc(name)).append("\"");
                sb.append(" tilewidth=\"").append(TILE_IMG_W).append("\"");
                sb.append(" tileheight=\"").append(TILE_IMG_H).append("\">\n");
                TilesetInfo td2 = tilesDirRegistry.get(name);
                String src2 = (td2 != null) ? td2.sourcePath() : "../Tiles/" + name + ".png";
                sb.append("  <image source=\"").append(xmlEsc(src2)).append("\"/>\n");
                sb.append(" </tileset>\n");
            }
        }

        // <layer> — emit all sublayers for non-empty levels only.
        // Level 0 (ground floor) is always emitted.  Upper levels (1+) are skipped
        // entirely when every one of their sublayers is all-zero — they are unused
        // floors that would just bloat the file.
        // Overflow sublayers (slots NUM_SUBLAYERS..NUM_ALL_SUBLAYERS-1) represent
        // lotpack entries beyond the 6 named slots; they are written as extra TMX
        // layers named "{lv}_Extra0", "{lv}_Extra1", … only when non-empty.
        int layerId = 1;
        for (int lv = 0; lv < header.numLevels; lv++) {
            // Check whether this level has any tile data at all (named + overflow slots)
            if (lv > 0) {
                boolean anyData = false;
                for (int sl = 0; sl < NUM_ALL_SUBLAYERS; sl++) {
                    if (!isEmpty(tiles[lv][sl])) { anyData = true; break; }
                }
                if (!anyData) {
                    layerId += NUM_SUBLAYERS;  // keep layerId counter consistent (named slots only)
                    continue;
                }
            }
            // Emit the 6 named sublayers.
            for (int sl = 0; sl < NUM_SUBLAYERS; sl++) {
                String layerName = lv + "_" + SUBLAYERS[sl];
                sb.append(" <layer id=\"").append(layerId++).append("\"");
                sb.append(" name=\"").append(layerName).append("\"");
                sb.append(" width=\"300\" height=\"300\">\n");
                sb.append("  <data encoding=\"base64\" compression=\"zlib\">\n");
                sb.append("   ").append(encodeLayerData(tiles[lv][sl], header, gidMap));
                sb.append("\n  </data>\n");
                sb.append(" </layer>\n");
            }
            // Emit overflow sublayers (lotpack entries beyond the 6 standard slots).
            // These are written as extra layers so no tile data is silently dropped.
            for (int ovsl = NUM_SUBLAYERS; ovsl < NUM_ALL_SUBLAYERS; ovsl++) {
                if (isEmpty(tiles[lv][ovsl])) continue;  // omit if entirely empty
                String layerName = lv + "_Extra" + (ovsl - NUM_SUBLAYERS);
                sb.append(" <layer id=\"").append(layerId++).append("\"");
                sb.append(" name=\"").append(layerName).append("\"");
                sb.append(" width=\"300\" height=\"300\">\n");
                sb.append("  <data encoding=\"base64\" compression=\"zlib\">\n");
                sb.append("   ").append(encodeLayerData(tiles[lv][ovsl], header, gidMap));
                sb.append("\n  </data>\n");
                sb.append(" </layer>\n");
            }
        }

        // <objectgroup name="0_Lots"> -- one <object name="lot"> per building.
        // lotheader rr->x is in tile-width (64px) units: pixel_x = tile_x * MAP_TILE_W (64)
        // lotheader rr->y is in tile-height (32px) units: pixel_y = tile_y * MAP_TILE_H (32)
        // When --excludebuilding is active the entire 0_Lots group is omitted because
        // there are no .tbx files to reference.
        if (!excludeBuilding) {
        if (lotEntries.isEmpty()) {
            sb.append(" <objectgroup name=\"0_Lots\" width=\"300\" height=\"300\"/>\n");
        } else {
            sb.append(" <objectgroup name=\"0_Lots\" width=\"300\" height=\"300\">\n");
            for (String entry : lotEntries) {
                String[] parts = entry.split("\\|");
                String relTbx = parts[0];
                int lox = Integer.parseInt(parts[1]);
                int loy = Integer.parseInt(parts[2]);
                int lbw = Integer.parseInt(parts[3]);
                int lbh = Integer.parseInt(parts[4]);
                sb.append("  <object name=\"lot\" type=\"").append(xmlEsc(relTbx)).append("\"");
                sb.append(" x=\"").append(lox * MAP_TILE_W).append("\"");
                sb.append(" y=\"").append(loy * MAP_TILE_H).append("\"");
                sb.append(" width=\"").append((lbw + 1) * MAP_TILE_W).append("\"");
                sb.append(" height=\"").append((lbh + 1) * MAP_TILE_H).append("\"/>\n");
            }
            sb.append(" </objectgroup>\n");
        }
        } // end if (!excludeBuilding) for 0_Lots


        // <bmp-settings> — WorldEd terrain-generation metadata.
        // We emit a minimal flat bmp-image (all pixels = 2 = "Medium Grass") as a
        // generic floor placeholder. WorldEd can re-generate this from the actual
        // Rules/Blends files.  The pixel data is gzip-compressed, base64-encoded.
        sb.append(buildBmpSettings());

        sb.append("</map>\n");
        Files.writeString(out, sb, StandardCharsets.UTF_8);
        log("  Written " + out);
    }

    // ── Layer data encoder ────────────────────────────────────────────────────
    // 300×300 uint32-LE GIDs, row-major (y outer, x inner),
    // zlib-deflated, then base64-encoded.

    static String encodeLayerData(int[][] layer, LotHeader hdr,
                                  Map<String, Integer> gidMap) throws IOException {
        int cellH = hdr.chunksY() * hdr.chunkH;
        int cellW = hdr.chunksX() * hdr.chunkW;

        ByteBuffer buf = ByteBuffer.allocate(CELL_TILES * CELL_TILES * 4)
                                   .order(ByteOrder.LITTLE_ENDIAN);
        for (int row = 0; row < CELL_TILES; row++) {
            for (int col = 0; col < CELL_TILES; col++) {
                int gid = 0;
                if (layer != null && row < cellH && col < cellW) {
                    int ti = layer[row][col];
                    if (ti >= 0 && ti < hdr.tileNames.size())
                        gid = gidMap.getOrDefault(hdr.tileNames.get(ti), 0);
                }
                buf.putInt(gid);
            }
        }

        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION);
        def.setInput(buf.array());
        def.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        while (!def.finished()) out.write(tmp, 0, def.deflate(tmp));
        def.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ── BMP-settings builder ─────────────────────────────────────────────────
    // Emits the <bmp-settings> block (WorldEd terrain ruleset) and a single
    // <bmp-image> (the generic floor, color rgb="90 100 35" = Dark Grass) verbatim
    // from the canonical MyMap_1_1.tmx template.  WorldEd will re-process both
    // with its Rules.txt / Blends.txt on next open/generate.
    static String buildBmpSettings() {
        // Only emit <bmp-settings> — the rules/blends/aliases WorldEd uses to
        // regenerate floor and vegetation layers from the bmp colour map.
        // The <bmp-image> tag is intentionally EXCLUDED: when it is present,
        // WorldEd reserves 0_Floor for the bmp render and shifts all lotpack
        // entries up by one sublayer, making 0_Floor unwritable.  Without it,
        // lotpack entry 0 lands correctly in 0_Floor.
        return BMP_SETTINGS;
    }

    // Verbatim <bmp-settings> block from the canonical template — all aliases,
    // rules, and blends WorldEd uses to regenerate floor/vegetation layers.
    // The companion <bmp-image> is NOT emitted (see buildBmpSettings above).
    static final String BMP_SETTINGS =
            " <bmp-settings version=\"1\">\n" +
            "  <rules-file file=\"../WorldEd/Rules.txt\"/>\n" +
            "  <blends-file file=\"../WorldEd/Blends.txt\"/>\n" +
            "  <edges-everywhere value=\"false\"/>\n" +
            "  <aliases>\n" +
            "   <alias name=\"lightgrass_n\" tiles=\"blends_natural_01_056 blends_natural_01_060\"/>\n" +
            "   <alias name=\"lightgrass_w\" tiles=\"blends_natural_01_057 blends_natural_01_061\"/>\n" +
            "   <alias name=\"lightgrass_e\" tiles=\"blends_natural_01_058 blends_natural_01_062\"/>\n" +
            "   <alias name=\"lightgrass_s\" tiles=\"blends_natural_01_059 blends_natural_01_063\"/>\n" +
            "   <alias name=\"medgrass_n\" tiles=\"blends_natural_01_040 blends_natural_01_044\"/>\n" +
            "   <alias name=\"medgrass_w\" tiles=\"blends_natural_01_041 blends_natural_01_045\"/>\n" +
            "   <alias name=\"medgrass_e\" tiles=\"blends_natural_01_042 blends_natural_01_046\"/>\n" +
            "   <alias name=\"medgrass_s\" tiles=\"blends_natural_01_043 blends_natural_01_047\"/>\n" +
            "   <alias name=\"darkgrass_n\" tiles=\"blends_natural_01_024 blends_natural_01_028\"/>\n" +
            "   <alias name=\"darkgrass_w\" tiles=\"blends_natural_01_025 blends_natural_01_029\"/>\n" +
            "   <alias name=\"darkgrass_e\" tiles=\"blends_natural_01_026 blends_natural_01_030\"/>\n" +
            "   <alias name=\"darkgrass_s\" tiles=\"blends_natural_01_027 blends_natural_01_031\"/>\n" +
            "   <alias name=\"sand_n\" tiles=\"blends_natural_01_008 blends_natural_01_012\"/>\n" +
            "   <alias name=\"sand_w\" tiles=\"blends_natural_01_009 blends_natural_01_013\"/>\n" +
            "   <alias name=\"sand_e\" tiles=\"blends_natural_01_010 blends_natural_01_014\"/>\n" +
            "   <alias name=\"sand_s\" tiles=\"blends_natural_01_011 blends_natural_01_015\"/>\n" +
            "   <alias name=\"medlonggrass\" tiles=\"blends_grassoverlays_01_024 blends_grassoverlays_01_025 blends_grassoverlays_01_026 blends_grassoverlays_01_027 blends_grassoverlays_01_028 blends_grassoverlays_01_029\"/>\n" +
            "   <alias name=\"medmedgrass\" tiles=\"blends_grassoverlays_01_032 blends_grassoverlays_01_033 blends_grassoverlays_01_034 blends_grassoverlays_01_035 blends_grassoverlays_01_036 blends_grassoverlays_01_037\"/>\n" +
            "   <alias name=\"medshortgrass\" tiles=\"blends_grassoverlays_01_040 blends_grassoverlays_01_041 blends_grassoverlays_01_042 blends_grassoverlays_01_043 blends_grassoverlays_01_044 blends_grassoverlays_01_045\"/>\n" +
            "   <alias name=\"lightlonggrass\" tiles=\"blends_grassoverlays_01_048 blends_grassoverlays_01_049 blends_grassoverlays_01_050 blends_grassoverlays_01_051 blends_grassoverlays_01_052 blends_grassoverlays_01_053\"/>\n" +
            "   <alias name=\"lightmedgrass\" tiles=\"blends_grassoverlays_01_056 blends_grassoverlays_01_057 blends_grassoverlays_01_058 blends_grassoverlays_01_059 blends_grassoverlays_01_060 blends_grassoverlays_01_061\"/>\n" +
            "   <alias name=\"lightshortgrass\" tiles=\"blends_grassoverlays_01_064 blends_grassoverlays_01_065 blends_grassoverlays_01_066 blends_grassoverlays_01_067 blends_grassoverlays_01_068 blends_grassoverlays_01_069\"/>\n" +
            "   <alias name=\"darklonggrass\" tiles=\"blends_grassoverlays_01_000 blends_grassoverlays_01_001 blends_grassoverlays_01_002 blends_grassoverlays_01_003 blends_grassoverlays_01_004 blends_grassoverlays_01_005\"/>\n" +
            "   <alias name=\"darkmedgrass\" tiles=\"blends_grassoverlays_01_008 blends_grassoverlays_01_009 blends_grassoverlays_01_010 blends_grassoverlays_01_011 blends_grassoverlays_01_012 blends_grassoverlays_01_013\"/>\n" +
            "   <alias name=\"darkshortgrass\" tiles=\"blends_grassoverlays_01_016 blends_grassoverlays_01_017 blends_grassoverlays_01_018 blends_grassoverlays_01_019 blends_grassoverlays_01_020 blends_grassoverlays_01_021\"/>\n" +
            "   <alias name=\"street\" tiles=\"blends_street_01_096 blends_street_01_101 blends_street_01_102 blends_street_01_103\"/>\n" +
            "   <alias name=\"street2\" tiles=\"blends_street_01_080 blends_street_01_085 blends_street_01_086 blends_street_01_087\"/>\n" +
            "   <alias name=\"lightgravel\" tiles=\"blends_street_01_048 blends_street_01_053 blends_street_01_054 blends_street_01_055\"/>\n" +
            "   <alias name=\"pothole\" tiles=\"blends_street_01_016 blends_street_01_021\"/>\n" +
            "   <alias name=\"dirt\" tiles=\"blends_natural_01_064 blends_natural_01_069 blends_natural_01_070 blends_natural_01_071\"/>\n" +
            "   <alias name=\"dirtgrass\" tiles=\"blends_natural_01_080 blends_natural_01_085 blends_natural_01_086 blends_natural_01_087\"/>\n" +
            "   <alias name=\"sand\" tiles=\"blends_natural_01_000 blends_natural_01_005 blends_natural_01_006 blends_natural_01_007\"/>\n" +
            "   <alias name=\"sandblends\" tiles=\"blends_natural_01_008 blends_natural_01_009 blends_natural_01_010 blends_natural_01_011 blends_natural_01_001 blends_natural_01_004 blends_natural_01_003 blends_natural_01_002\"/>\n" +
            "   <alias name=\"darkgrassblends\" tiles=\"blends_natural_01_024 blends_natural_01_025 blends_natural_01_026 blends_natural_01_027 blends_natural_01_017 blends_natural_01_020 blends_natural_01_019 blends_natural_01_018\"/>\n" +
            "   <alias name=\"medgrassblends\" tiles=\"blends_natural_01_040 blends_natural_01_041 blends_natural_01_042 blends_natural_01_043 blends_natural_01_033 blends_natural_01_036 blends_natural_01_035 blends_natural_01_034\"/>\n" +
            "   <alias name=\"lightgrassblends\" tiles=\"blends_natural_01_056 blends_natural_01_057 blends_natural_01_058 blends_natural_01_059 blends_natural_01_049 blends_natural_01_052 blends_natural_01_051 blends_natural_01_059\"/>\n" +
            "   <alias name=\"darkgrass\" tiles=\"blends_natural_01_016 blends_natural_01_021 blends_natural_01_022 blends_natural_01_023\"/>\n" +
            "   <alias name=\"medgrass\" tiles=\"blends_natural_01_032 blends_natural_01_037 blends_natural_01_038 blends_natural_01_039\"/>\n" +
            "   <alias name=\"lightgrass\" tiles=\"blends_natural_01_048 blends_natural_01_053 blends_natural_01_054 blends_natural_01_055\"/>\n" +
            "   <alias name=\"water\" tiles=\"blends_natural_02_000 blends_natural_02_005 blends_natural_02_006 blends_natural_02_007\"/>\n" +
            "  </aliases>\n" +
            "  <rules>\n" +
            "   <rule label=\"Dark Grass\" bitmapIndex=\"0\" color=\"90 100 35\" tileChoices=\"darkgrass\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Medium Grass\" bitmapIndex=\"0\" color=\"117 117 47\" tileChoices=\"medgrass\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Light Grass\" bitmapIndex=\"0\" color=\"145 135 60\" tileChoices=\"lightgrass\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Sand\" bitmapIndex=\"0\" color=\"210 200 160\" tileChoices=\"sand\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Light Asphalt\" bitmapIndex=\"0\" color=\"165 160 140\" tileChoices=\"lightgravel\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Dark Asphalt (main roads)\" bitmapIndex=\"0\" color=\"100 100 100\" tileChoices=\"street2\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Medium Asphalt\" bitmapIndex=\"0\" color=\"120 120 120\" tileChoices=\"street\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Gravel Dirt\" bitmapIndex=\"0\" color=\"140 70 15\" tileChoices=\"floors_exterior_natural_01_012\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Dirt\" bitmapIndex=\"0\" color=\"120 70 20\" tileChoices=\"dirt\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Dirt Grass\" bitmapIndex=\"0\" color=\"80 55 20\" tileChoices=\"dirtgrass\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Dark Pothole\" bitmapIndex=\"0\" color=\"110 100 100\" tileChoices=\"blends_street_01_000\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Light Pothole\" bitmapIndex=\"0\" color=\"130 120 120\" tileChoices=\"pothole\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Water\" bitmapIndex=\"0\" color=\"0 138 255\" tileChoices=\"water\" targetLayer=\"0_Floor\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Trees\" bitmapIndex=\"1\" color=\"255 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011\" targetLayer=\"0_Vegetation\" condition=\"0 0 0\"/>\n" +
            "   <rule label=\"Dense Trees + Dark grass\" bitmapIndex=\"1\" color=\"200 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 darklonggrass\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Dense Trees + Medium grass\" bitmapIndex=\"1\" color=\"200 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 medlonggrass\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Dense Trees + Light grass\" bitmapIndex=\"1\" color=\"200 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 lightlonggrass\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "   <rule label=\"Trees + Dark grass\" bitmapIndex=\"1\" color=\"127 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 darklonggrass darklonggrass darkmedgrass darkshortgrass\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Trees + Medium grass\" bitmapIndex=\"1\" color=\"127 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 medlonggrass medlonggrass medmedgrass medshortgrass\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Trees + Light grass\" bitmapIndex=\"1\" color=\"127 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 lightlonggrass lightlonggrass lightmedgrass lightshortgrass\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "   <rule label=\"Fir Trees + Dark grass\" bitmapIndex=\"1\" color=\"64 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 darklonggrass darklonggrass darkmedgrass darkshortgrass darkmedgrass darkshortgrass\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Fir Trees + Medium grass\" bitmapIndex=\"1\" color=\"64 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 medlonggrass medlonggrass medmedgrass medshortgrass medmedgrass medshortgrass\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Fir Trees + Light grass\" bitmapIndex=\"1\" color=\"64 0 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_009 lightlonggrass lightlonggrass lightmedgrass lightshortgrass lightmedgrass lightshortgrass\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "   <rule label=\"Grass (all types)\" bitmapIndex=\"1\" color=\"0 255 0\" tileChoices=\"darklonggrass darkmedgrass darkshortgrass darklonggrass darkmedgrass darkshortgrass darklonggrass darkmedgrass darkshortgrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Light long grass\" bitmapIndex=\"1\" color=\"0 255 0\" tileChoices=\"lightshortgrass lightmedgrass lightlonggrass lightshortgrass lightmedgrass lightlonggrass lightshortgrass lightmedgrass lightlonggrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "   <rule label=\"\" bitmapIndex=\"1\" color=\"0 255 0\" tileChoices=\"medshortgrass medmedgrass medlonggrass medshortgrass medmedgrass medlonggrass medshortgrass medmedgrass medlonggrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Grass + Few Trees (dark)\" bitmapIndex=\"1\" color=\"0 128 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_010 darklonggrass darklonggrass darkmedgrass darkshortgrass darklonggrass darklonggrass darklonggrass darkmedgrass darkmedgrass vegetation_trees_01_008 vegetation_trees_01_010 darklonggrass darklonggrass darkmedgrass darklonggrass darklonggrass darkmedgrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Grass + Few Trees (medium)\" bitmapIndex=\"1\" color=\"0 128 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_010 medlonggrass medlonggrass medmedgrass medshortgrass medlonggrass medlonggrass medlonggrass medmedgrass medmedgrass vegetation_trees_01_008 vegetation_trees_01_010 medlonggrass medlonggrass medmedgrass medlonggrass medlonggrass medmedgrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Grass + Few Trees (light)\" bitmapIndex=\"1\" color=\"0 128 0\" tileChoices=\"vegetation_trees_01_008 vegetation_trees_01_010 lightlonggrass lightlonggrass lightmedgrass lightshortgrass lightlonggrass lightlonggrass lightlonggrass lightmedgrass lightmedgrass vegetation_trees_01_008 vegetation_trees_01_010 lightlonggrass lightlonggrass lightmedgrass lightlonggrass lightlonggrass lightmedgrass vegetation_groundcover_01_018 vegetation_groundcover_01_019 vegetation_groundcover_01_021 vegetation_groundcover_01_022 vegetation_groundcover_01_023\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "   <rule label=\"Bushes, Grass, Few Trees (dark)\" bitmapIndex=\"1\" color=\"255 0 255\" tileChoices=\"vegetation_foliage_01_008 vegetation_foliage_01_009 vegetation_foliage_01_010 vegetation_foliage_01_011 vegetation_foliage_01_012 vegetation_foliage_01_013 vegetation_foliage_01_014 vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 darklonggrass darklonggrass darkmedgrass darkshortgrass\" targetLayer=\"0_Vegetation\" condition=\"90 100 35\"/>\n" +
            "   <rule label=\"Bushes, Grass, Few Trees (medium)\" bitmapIndex=\"1\" color=\"255 0 255\" tileChoices=\"vegetation_foliage_01_008 vegetation_foliage_01_009 vegetation_foliage_01_010 vegetation_foliage_01_011 vegetation_foliage_01_012 vegetation_foliage_01_013 vegetation_foliage_01_014 vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 medlonggrass medlonggrass medmedgrass medshortgrass\" targetLayer=\"0_Vegetation\" condition=\"117 117 47\"/>\n" +
            "   <rule label=\"Bushes, Grass, Few Trees (light)\" bitmapIndex=\"1\" color=\"255 0 255\" tileChoices=\"vegetation_foliage_01_008 vegetation_foliage_01_009 vegetation_foliage_01_010 vegetation_foliage_01_011 vegetation_foliage_01_012 vegetation_foliage_01_013 vegetation_foliage_01_014 vegetation_trees_01_008 vegetation_trees_01_009 vegetation_trees_01_010 vegetation_trees_01_011 lightlonggrass lightlonggrass lightmedgrass lightshortgrass\" targetLayer=\"0_Vegetation\" condition=\"145 135 60\"/>\n" +
            "  </rules>\n" +
            "  <blends>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"darkgrass\" blendTile=\"darkgrass_n\" dir=\"n\" ExclusionList=\"water\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"darkgrass\" blendTile=\"darkgrass_w\" dir=\"w\" ExclusionList=\"water\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"darkgrass\" blendTile=\"darkgrass_e\" dir=\"e\" ExclusionList=\"water\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"darkgrass\" blendTile=\"darkgrass_s\" dir=\"s\" ExclusionList=\"water\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"darkgrass\" blendTile=\"blends_natural_01_017\" dir=\"nw\" ExclusionList=\"water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"darkgrass\" blendTile=\"blends_natural_01_020\" dir=\"ne\" ExclusionList=\"water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"darkgrass\" blendTile=\"blends_natural_01_019\" dir=\"sw\" ExclusionList=\"water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"darkgrass\" blendTile=\"blends_natural_01_018\" dir=\"se\" ExclusionList=\"water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"medgrass\" blendTile=\"medgrass_n\" dir=\"n\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"medgrass\" blendTile=\"medgrass_w\" dir=\"w\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"medgrass\" blendTile=\"medgrass_e\" dir=\"e\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"medgrass\" blendTile=\"medgrass_s\" dir=\"s\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"medgrass\" blendTile=\"blends_natural_01_033\" dir=\"nw\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"medgrass\" blendTile=\"blends_natural_01_036\" dir=\"ne\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"medgrass\" blendTile=\"blends_natural_01_035\" dir=\"sw\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"medgrass\" blendTile=\"blends_natural_01_034\" dir=\"se\" ExclusionList=\"darkgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"lightgrass\" blendTile=\"lightgrass_n\" dir=\"n\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"lightgrass\" blendTile=\"lightgrass_w\" dir=\"w\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"lightgrass\" blendTile=\"lightgrass_e\" dir=\"e\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"lightgrass\" blendTile=\"lightgrass_s\" dir=\"s\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"lightgrass\" blendTile=\"blends_natural_01_049\" dir=\"nw\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"lightgrass\" blendTile=\"blends_natural_01_052\" dir=\"ne\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"lightgrass\" blendTile=\"blends_natural_01_051\" dir=\"sw\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"lightgrass\" blendTile=\"blends_natural_01_050\" dir=\"se\" ExclusionList=\"darkgrass medgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"sand\" blendTile=\"sand_n\" dir=\"n\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"sand\" blendTile=\"sand_w\" dir=\"w\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"sand\" blendTile=\"sand_e\" dir=\"e\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"sand\" blendTile=\"sand_s\" dir=\"s\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"sand\" blendTile=\"blends_natural_01_001\" dir=\"nw\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"sand\" blendTile=\"blends_natural_01_004\" dir=\"ne\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"sand\" blendTile=\"blends_natural_01_003\" dir=\"sw\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"sand\" blendTile=\"blends_natural_01_002\" dir=\"se\" ExclusionList=\"darkgrass medgrass lightgrass water\" exclude2=\"street_trafficlines_001 0_FloorOverlay street_trafficlines_001 0_FloorOverlay2 street_trafficlines_001 0_FloorOverlay3 street_trafficlines_001 0_FloorOverlay4\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_008\" dir=\"n\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_009\" dir=\"w\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_010\" dir=\"e\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_011\" dir=\"s\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_001\" dir=\"nw\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_002\" dir=\"se\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_003\" dir=\"sw\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"blends_street_01_000\" blendTile=\"blends_street_01_004\" dir=\"ne\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"pothole\" blendTile=\"blends_street_01_024\" dir=\"n\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"pothole\" blendTile=\"blends_street_01_025\" dir=\"w\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"pothole\" blendTile=\"blends_street_01_026\" dir=\"e\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"pothole\" blendTile=\"blends_street_01_027\" dir=\"s\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"pothole\" blendTile=\"blends_street_01_017\" dir=\"nw\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"pothole\" blendTile=\"blends_street_01_018\" dir=\"se\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"pothole\" blendTile=\"blends_street_01_019\" dir=\"sw\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"pothole\" blendTile=\"blends_street_01_020\" dir=\"ne\" ExclusionList=\"lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"dirt\" blendTile=\"blends_natural_01_072\" dir=\"n\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"dirt\" blendTile=\"blends_natural_01_073\" dir=\"w\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"dirt\" blendTile=\"blends_natural_01_074\" dir=\"e\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"dirt\" blendTile=\"blends_natural_01_075\" dir=\"s\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"dirt\" blendTile=\"blends_natural_01_065\" dir=\"nw\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"dirt\" blendTile=\"blends_natural_01_066\" dir=\"se\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"dirt\" blendTile=\"blends_natural_01_067\" dir=\"sw\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"dirt\" blendTile=\"blends_natural_01_068\" dir=\"ne\" ExclusionList=\"water lightgrass medgrass darkgrass sand dirtgrass\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_088\" dir=\"n\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_089\" dir=\"w\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_090\" dir=\"e\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_091\" dir=\"s\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_081\" dir=\"nw\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_082\" dir=\"se\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_083\" dir=\"sw\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"dirtgrass\" blendTile=\"blends_natural_01_084\" dir=\"ne\" ExclusionList=\"water lightgrass medgrass darkgrass sand\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"water\" blendTile=\"blends_natural_02_008\" dir=\"n\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"water\" blendTile=\"blends_natural_02_009\" dir=\"w\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"water\" blendTile=\"blends_natural_02_010\" dir=\"e\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"water\" blendTile=\"blends_natural_02_011\" dir=\"s\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay\" mainTile=\"water\" blendTile=\"blends_natural_02_001\" dir=\"nw\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay2\" mainTile=\"water\" blendTile=\"blends_natural_02_002\" dir=\"se\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay3\" mainTile=\"water\" blendTile=\"blends_natural_02_003\" dir=\"sw\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "   <blend targetLayer=\"0_FloorOverlay4\" mainTile=\"water\" blendTile=\"blends_natural_02_004\" dir=\"ne\" ExclusionList=\"\" exclude2=\"\"/>\n" +
            "  </blends>\n" +
            " </bmp-settings>\n" +
            "";


    /** Encodes a 300×300 grid of identical uint32-LE values as gzip+base64. */
    static String generateBmpPixels(int value) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            // GZIPOutputStream with default header (mtime=0 for reproducibility)
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(baos)) {
                ByteBuffer buf = ByteBuffer.allocate(CELL_TILES * CELL_TILES * 4)
                                           .order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < CELL_TILES * CELL_TILES; i++) buf.putInt(value);
                gz.write(buf.array());
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException ex) {
            throw new RuntimeException("generateBmpPixels failed", ex);
        }
    }

    // ── Binary helpers ────────────────────────────────────────────────────────

    static String readPzString(DataInputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0x0A) buf.write(b);
        return buf.toString(StandardCharsets.UTF_8);
    }

    static int readInt32LE(DataInputStream in) throws IOException {
        byte[] b = new byte[4]; in.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static int readInt32LE(RandomAccessFile f) throws IOException {
        byte[] b = new byte[4]; f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    static long readInt64LE(RandomAccessFile f) throws IOException {
        byte[] b = new byte[8]; f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    // ── Misc helpers ──────────────────────────────────────────────────────────

    /** "blends_natural_01_5" → ["blends_natural_01", "5"] */
    static String[] splitTileName(String name) {
        int i = name.lastIndexOf('_');
        if (i > 0 && name.substring(i + 1).matches("\\d+"))
            return new String[]{ name.substring(0, i), name.substring(i + 1) };
        return new String[]{ name, "0" };
    }

    static boolean isEmpty(int[][] layer) {
        if (layer == null) return true;
        for (int[] row : layer) for (int v : row) if (v >= 0) return false;
        return true;
    }

    static String xmlEsc(String s) {
        return s.replace("&","&amp;").replace("<","&lt;")
                .replace(">","&gt;").replace("\"","&quot;");
    }

    static int parseIntSafe(String s) {
        try { return (int) Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Parse cell X and Y from a lotheader filename like "3_7.lotheader" → {3, 7}. */
    static int[] parseCellCoords(String filename) {
        // Strip extension
        String base = filename.replaceAll("\\.[^.]+$", "");
        String[] parts = base.split("_", 2);
        try {
            return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };
        } catch (Exception e) {
            System.err.println("WARNING: Could not parse cell coords from '" + filename + "', defaulting to 0,0");
            return new int[]{0, 0};
        }
    }

    static String deriveOutputName(Path lotheader) {
        // "0_0.lotheader" → "MyMap_0_0.tmx"
        return "MyMap_" + lotheader.getFileName().toString().replace(".lotheader", "") + ".tmx";
    }

    /** Find exactly one file matching a glob in a directory, or die with a clear message. */
    static Path findOne(Path dir, String glob, String label) throws IOException {
        List<Path> matches;
        try (var ds = Files.newDirectoryStream(dir, glob)) {
            matches = new ArrayList<>();
            ds.forEach(matches::add);
        }
        if (matches.isEmpty())
            die("No " + label + " file (" + glob + ") found in: " + dir);
        if (matches.size() > 1)
            die("Multiple " + label + " files found in " + dir
                + " — use --lotheader/--lotpack to specify explicitly.\n  "
                + matches.stream().map(Path::getFileName).map(Object::toString)
                         .reduce((a, b) -> a + "\n  " + b).orElse(""));
        return matches.get(0);
    }

    static void die(String msg) {
        System.err.println("ERROR: " + msg);
        System.exit(1);
    }

    static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            // Boolean flag: no following token, or next token is itself a flag.
            if (i + 1 >= args.length || args[i + 1].startsWith("--")) {
                m.put(key, "true");   // presence flag — value is irrelevant
            } else {
                m.put(key, args[i + 1]);
                i++;  // consume the value token
            }
        }
        return m;
    }

    static void log(String s) { System.out.println(s); }
}