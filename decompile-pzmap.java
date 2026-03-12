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
 *
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

        // ── Map-folder mode: process every cell found in the directory ─────────
        if (opts.containsKey("mapdir")) {
            Path mapDir = Path.of(opts.get("mapdir"));
            if (!Files.isDirectory(mapDir))
                die("--mapdir is not a directory: " + mapDir);

            // Output folder: --output overrides, otherwise same as mapdir.
            Path outDir = opts.containsKey("output") ? Path.of(opts.get("output")) : mapDir;
            Files.createDirectories(outDir);

            // Discover all lotheader files; each one defines a cell.
            List<Path> lotheaders;
            try (var ds = Files.newDirectoryStream(mapDir, "*.lotheader")) {
                lotheaders = new ArrayList<>();
                ds.forEach(lotheaders::add);
            }
            // Sort for deterministic processing order (e.g. 0_0 before 1_0 before 0_1).
            lotheaders.sort((a, b) -> {
                int[] ca = parseCellCoords(a.getFileName().toString());
                int[] cb = parseCellCoords(b.getFileName().toString());
                return ca[0] != cb[0] ? Integer.compare(ca[0], cb[0]) : Integer.compare(ca[1], cb[1]);
            });
            if (lotheaders.isEmpty())
                die("No *.lotheader files found in: " + mapDir);

            log("Found " + lotheaders.size() + " cell(s) in " + mapDir);
            int processed = 0, failed = 0;
            for (Path lh : lotheaders) {
                int[] cc = parseCellCoords(lh.getFileName().toString());
                String cellTag = cc[0] + "_" + cc[1];
                // Expect world_X_Y.lotpack alongside the lotheader.
                Path lp = mapDir.resolve("world_" + cellTag + ".lotpack");
                if (!Files.exists(lp)) {
                    System.err.println("WARNING: No lotpack for " + lh.getFileName() + " (expected " + lp.getFileName() + ") — skipping.");
                    failed++;
                    continue;
                }
                Path outTmx = outDir.resolve("MyMap_" + cellTag + ".tmx");
                try {
                    log("\n== Cell " + cellTag + " ==================================");
                    processCell(lh, lp, outTmx, tilesDir, tilesetsRef);
                    processed++;
                } catch (Exception e) {
                    System.err.println("ERROR processing cell " + cellTag + ": " + e.getMessage());
                    failed++;
                }
            }
            log("\nDone. " + processed + " cell(s) written" + (failed > 0 ? ", " + failed + " skipped/failed" : "") + ".");
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
            System.err.println("    [--output   <output folder, default: same as --mapdir>]");
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
        processCell(lotheaderPath, lotpackPath, Path.of(outName), tilesDir, tilesetsRef);
        log("\nDone! Open " + outName + " in WorldEd / TileZed.");
    }

    // ── processCell: convert one (lotheader, lotpack) pair → TMX + TBX files ─

    static void processCell(Path lotheaderPath, Path lotpackPath, Path outPath,
                            Path tilesDir, Path tilesetsRef) throws Exception {

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
        if (tilesetsRef != null) {
            tilesetRegistry = readTilesetsFromTmx(tilesetsRef);
            log("      " + tilesetRegistry.size() + " tilesets read from " + tilesetsRef);
            if (tilesetRegistry.isEmpty())
                log("      WARNING: No tilesets found in reference TMX.");
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
        int[][][][] tiles = new int[header.numLevels][NUM_SUBLAYERS][cellH][cellW];
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
        List<BuildingData> buildings = extractBuildings(header, tiles, roomIds);

        // .tbx files go in a "buildings" subfolder next to the output TMX.
        // Named  X_Y_building_i.tbx  so multiple cells can share one folder.
        Path buildingsDir = outPath.getParent() != null
                ? outPath.getParent().resolve("buildings")
                : Path.of("buildings");
        if (!buildings.isEmpty())
            Files.createDirectories(buildingsDir);

        String cellTag = cellX + "_" + cellY;  // used in tbx filenames
        List<String> lotEntries = new ArrayList<>();
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

        // 5 — write TMX (building tiles already erased from 'tiles' by extractBuildings)
        log("[5/5] Writing " + outPath + " ...");
        writeTmx(outPath, header, tiles, tilesetRegistry, lotEntries);
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
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))  // case-sensitive: uppercase before lowercase, matching WorldEd order
                    .toList();
        }

        Map<String, TilesetInfo> result = new LinkedHashMap<>();
        for (Path png : pngs) {
            String filename = png.getFileName().toString();
            String name     = filename.substring(0, filename.length() - 4); // strip ".png"
            // relative path from tilesdir → used in <image source="../Tiles/...">
            String relPath  = dir.relativize(png).toString().replace('\\', '/');

            if (result.containsKey(name)) {
                log("      WARNING: duplicate tileset name '" + name + "' at " + relPath + ", skipping");
                continue;
            }

            int[] dims = readPngDimensions(png);
            if (dims == null) {
                log("      WARNING: could not read PNG dimensions for " + relPath + ", skipping");
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
     * Reads width and height from a PNG file by parsing only the IHDR chunk.
     * PNG structure: 8-byte signature + IHDR chunk (4-len, 4-type, 13-data, 4-crc).
     * IHDR data: width(4), height(4), bitDepth(1), colorType(1), ...
     * Returns [width, height] or null on error.
     */
    static int[] readPngDimensions(Path png) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(png))) {
            // Skip 8-byte PNG signature
            if (in.skip(8) != 8) return null;
            // Read chunk length (4 bytes, big-endian) — should be 13 for IHDR
            byte[] buf = new byte[4];
            if (in.read(buf) != 4) return null;
            // Read chunk type (4 bytes) — should be "IHDR"
            if (in.read(buf) != 4) return null;
            if (buf[0] != 'I' || buf[1] != 'H' || buf[2] != 'D' || buf[3] != 'R') return null;
            // Read width (4 bytes big-endian)
            if (in.read(buf) != 4) return null;
            int w = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16)
                  | ((buf[2] & 0xFF) << 8)  |  (buf[3] & 0xFF);
            // Read height (4 bytes big-endian)
            if (in.read(buf) != 4) return null;
            int h = ((buf[0] & 0xFF) << 24) | ((buf[1] & 0xFF) << 16)
                  | ((buf[2] & 0xFF) << 8)  |  (buf[3] & 0xFF);
            return new int[]{ w, h };
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
            // Zombie spawn grid -- discard (remainder of file)
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
                            int sl  = Math.min(e, NUM_SUBLAYERS - 1);
                            if (wx < cellW && wy < cellH && tiles[z][sl][wy][wx] < 0)
                                tiles[z][sl][wy][wx] = tid;
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

    static List<BuildingData> extractBuildings(LotHeader header, int[][][][] tiles, int[][][] roomIds) {
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
                        for (int sl = 0; sl < NUM_SUBLAYERS; sl++) {
                            if (gy < tiles[z][sl].length && gx < tiles[z][sl][gy].length) {
                                bd.buildingTiles[z][sl][by][bx] = tiles[z][sl][gy][gx];
                                tiles[z][sl][gy][gx] = -1;  // erase from TMX grid
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
                for (int by = 0; by < bh; by++)
                    for (int bx = 0; bx < bw; bx++) {
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


    // ── TMX writer ────────────────────────────────────────────────────────────

    static void writeTmx(Path out, LotHeader header, int[][][][] tiles,
                         Map<String, TilesetInfo> tilesetRegistry,
                         List<String> lotEntries) throws IOException {

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
                    // Add a placeholder entry so it gets emitted
                    tilesetRegistry.put(name, new TilesetInfo(name, name + ".png", "../Tiles/" + name + ".png", 1024, 2048, lastEnd));
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
                int imgW = TILESET_COLS * TILE_IMG_W;        // = 16 * 64 = 1024
                int imgH = rows * TILE_IMG_H;                // = rows * 128
                tilesetRegistry.put(name, new TilesetInfo(name, name + ".png", imgW, imgH));
                nextGid += blockSize;
            }
            // Fallback: tilesets from registry not yet in allFirstGids (tilesdir scan)
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
                sb.append("  <image source=\"").append(xmlEsc(info.sourcePath())).append("\"");
                sb.append(" width=\"").append(info.imgW()).append("\"");
                sb.append(" height=\"").append(info.imgH()).append("\"/>\n");
            } else {
                sb.append("  <image source=\"../Tiles/").append(xmlEsc(name)).append(".png\"/>\n");
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
                sb.append("  <image source=\"../Tiles/").append(xmlEsc(name)).append(".png\"/>\n");
                sb.append(" </tileset>\n");
            }
        }

        // <layer> — emit all 6 sublayers for non-empty levels only.
        // Level 0 (ground floor) is always emitted.  Upper levels (1+) are skipped
        // entirely when every one of their sublayers is all-zero — they are unused
        // floors that would just bloat the file.
        int layerId = 1;
        for (int lv = 0; lv < header.numLevels; lv++) {
            // Check whether this level has any tile data at all
            if (lv > 0) {
                boolean anyData = false;
                for (int sl = 0; sl < NUM_SUBLAYERS; sl++) {
                    if (!isEmpty(tiles[lv][sl])) { anyData = true; break; }
                }
                if (!anyData) {
                    layerId += NUM_SUBLAYERS;  // keep layerId counter consistent
                    continue;
                }
            }
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
        }

        // <objectgroup name="0_Lots"> -- one <object name="lot"> per building.
        // lotheader rr->x is in tile-width (64px) units: pixel_x = tile_x * MAP_TILE_W (64)
        // lotheader rr->y is in tile-height (32px) units: pixel_y = tile_y * MAP_TILE_H (32)
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
        for (int i = 0; i < args.length - 1; i++)
            if (args[i].startsWith("--")) m.put(args[i].substring(2), args[i + 1]);
        return m;
    }

    static void log(String s) { System.out.println(s); }
}