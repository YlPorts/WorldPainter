use flate2::read::{DeflateDecoder, ZlibDecoder};
use flate2::write::{DeflateEncoder, ZlibEncoder};
use flate2::Compression;
use jni::objects::{JByteArray, JClass, JIntArray, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use rusty_leveldb::{Compressor, CompressorList, DB, Options};
use std::fs;
use std::io::{Read, Write};
use std::path::Path;
use std::rc::Rc;
use std::time::{SystemTime, UNIX_EPOCH};

const TAG_VERSION: u8 = 0x2c;
const TAG_DATA_2D: u8 = 0x2d;
const TAG_SUBCHUNK: u8 = 0x2f;
const TAG_FINALIZED: u8 = 0x36;
const RAW_DEFLATE_ID: u8 = 4;
const ZLIB_ID: u8 = 2;
const BLOCK_STATE_VERSION: i32 = 18_105_860;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Block {
    Air,
    Bedrock,
    Stone,
    Dirt,
    Grass,
    Sand,
    Water,
}

impl Block {
    fn name(self) -> &'static str {
        match self {
            Block::Air => "minecraft:air",
            Block::Bedrock => "minecraft:bedrock",
            Block::Stone => "minecraft:stone",
            Block::Dirt => "minecraft:dirt",
            Block::Grass => "minecraft:grass_block",
            Block::Sand => "minecraft:sand",
            Block::Water => "minecraft:water",
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_ylports_worldpainter_NativeBedrock_exportWorld(
    mut env: JNIEnv,
    _class: JClass,
    output_directory: JString,
    world_name: JString,
    width: jint,
    depth: jint,
    sea_level: jint,
    heights: JIntArray,
    materials: JByteArray,
    water_mask: JByteArray,
) -> jstring {
    let result = (|| -> Result<(), String> {
        let output: String = env
            .get_string(&output_directory)
            .map_err(|e| format!("Ruta inválida: {e}"))?
            .into();
        let name: String = env
            .get_string(&world_name)
            .map_err(|e| format!("Nombre inválido: {e}"))?
            .into();

        if width <= 0 || depth <= 0 || width > 4096 || depth > 4096 {
            return Err("Dimensiones fuera del rango soportado".to_string());
        }
        let expected = (width as usize)
            .checked_mul(depth as usize)
            .ok_or_else(|| "Dimensiones demasiado grandes".to_string())?;

        let mut height_values = vec![0i32; expected];
        env.get_int_array_region(&heights, 0, &mut height_values)
            .map_err(|e| format!("No se pudo leer el mapa de alturas: {e}"))?;

        let mut material_signed = vec![0i8; expected];
        env.get_byte_array_region(&materials, 0, &mut material_signed)
            .map_err(|e| format!("No se pudo leer el terreno: {e}"))?;
        let material_values: Vec<u8> = material_signed.into_iter().map(|v| v as u8).collect();

        let mut water_signed = vec![0i8; expected];
        env.get_byte_array_region(&water_mask, 0, &mut water_signed)
            .map_err(|e| format!("No se pudo leer el agua: {e}"))?;
        let water_values: Vec<u8> = water_signed.into_iter().map(|v| v as u8).collect();

        export_world(
            Path::new(&output),
            &name,
            width as usize,
            depth as usize,
            sea_level,
            &height_values,
            &material_values,
            &water_values,
        )
    })();

    let text = match result {
        Ok(()) => String::new(),
        Err(error) => error,
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn export_world(
    output: &Path,
    world_name: &str,
    width: usize,
    depth: usize,
    sea_level: i32,
    heights: &[i32],
    materials: &[u8],
    water: &[u8],
) -> Result<(), String> {
    if heights.len() != width * depth
        || materials.len() != heights.len()
        || water.len() != heights.len()
    {
        return Err("Los datos del mundo están incompletos".to_string());
    }

    fs::create_dir_all(output).map_err(|e| format!("No se pudo crear el mundo: {e}"))?;
    let db_path = output.join("db");
    fs::create_dir_all(&db_path).map_err(|e| format!("No se pudo crear LevelDB: {e}"))?;

    let options = Options {
        create_if_missing: true,
        compressor: RAW_DEFLATE_ID,
        compressor_list: bedrock_compressor_list(),
        max_file_size: 16 << 20,
        write_buffer_size: 16 << 20,
        max_open_files: 64,
        ..Options::default()
    };

    let mut db =
        DB::open(&db_path, options).map_err(|e| format!("No se pudo abrir LevelDB: {e:?}"))?;
    let chunk_count_x = (width + 15) / 16;
    let chunk_count_z = (depth + 15) / 16;

    for cz in 0..chunk_count_z {
        for cx in 0..chunk_count_x {
            write_chunk(
                &mut db,
                cx as i32,
                cz as i32,
                width,
                depth,
                sea_level,
                heights,
                materials,
                water,
            )?;
        }
    }

    db.flush()
        .map_err(|e| format!("No se pudo finalizar LevelDB: {e:?}"))?;
    drop(db);

    write_level_dat(
        output,
        world_name,
        width as i32 / 2,
        80,
        depth as i32 / 2,
    )?;
    fs::write(output.join("levelname.txt"), world_name.as_bytes())
        .map_err(|e| format!("No se pudo escribir levelname.txt: {e}"))?;

    Ok(())
}

fn write_chunk(
    db: &mut DB,
    cx: i32,
    cz: i32,
    width: usize,
    depth: usize,
    sea_level: i32,
    heights: &[i32],
    materials: &[u8],
    water: &[u8],
) -> Result<(), String> {
    db.put(&chunk_key(cx, cz, TAG_VERSION), &[40u8])
        .map_err(|e| format!("ChunkVersion ({cx},{cz}): {e:?}"))?;
    db.put(&chunk_key(cx, cz, TAG_FINALIZED), &2i32.to_le_bytes())
        .map_err(|e| format!("FinalizedState ({cx},{cz}): {e:?}"))?;

    let mut max_top = 0i32;
    for lz in 0..16usize {
        for lx in 0..16usize {
            let x = cx as isize * 16 + lx as isize;
            let z = cz as isize * 16 + lz as isize;
            if x < 0 || z < 0 || x as usize >= width || z as usize >= depth {
                continue;
            }
            let i = z as usize * width + x as usize;
            let h = heights[i].clamp(4, 120);
            let wet = water[i] != 0 || h < sea_level - 1;
            max_top = max_top.max(if wet { sea_level.max(h) } else { h });
        }
    }

    for sy in 0..=(max_top / 16) {
        let mut blocks = [Block::Air; 4096];
        let mut has_non_air = false;
        for lx in 0..16usize {
            for lz in 0..16usize {
                let x = cx as isize * 16 + lx as isize;
                let z = cz as isize * 16 + lz as isize;
                if x < 0 || z < 0 || x as usize >= width || z as usize >= depth {
                    continue;
                }
                let i = z as usize * width + x as usize;
                let h = heights[i].clamp(4, 120);
                let material = materials[i];
                let wet = water[i] != 0 || h < sea_level - 1;
                for ly in 0..16usize {
                    let y = sy * 16 + ly as i32;
                    let block = block_at(y, h, material, wet, sea_level);
                    if block != Block::Air {
                        has_non_air = true;
                    }
                    blocks[lx * 256 + lz * 16 + ly] = block;
                }
            }
        }
        if has_non_air {
            let encoded = encode_subchunk(&blocks)?;
            db.put(&subchunk_key(cx, cz, sy as i8), &encoded)
                .map_err(|e| format!("SubChunk ({cx},{cz},{sy}): {e:?}"))?;
        }
    }

    let data2d = encode_data2d(
        cx, cz, width, depth, sea_level, heights, materials, water,
    );
    db.put(&chunk_key(cx, cz, TAG_DATA_2D), &data2d)
        .map_err(|e| format!("Data2D ({cx},{cz}): {e:?}"))?;
    Ok(())
}

fn block_at(y: i32, height: i32, material: u8, wet: bool, sea_level: i32) -> Block {
    if y < 0 {
        return Block::Air;
    }
    if y == 0 {
        return Block::Bedrock;
    }
    if y > height {
        if wet && y <= sea_level {
            return Block::Water;
        }
        return Block::Air;
    }

    match material {
        1 => {
            if y >= height - 4 {
                Block::Sand
            } else {
                Block::Stone
            }
        }
        2 => Block::Stone,
        _ => {
            if y == height {
                Block::Grass
            } else if y >= height - 3 {
                Block::Dirt
            } else {
                Block::Stone
            }
        }
    }
}

fn encode_subchunk(blocks: &[Block; 4096]) -> Result<Vec<u8>, String> {
    let mut palette = vec![Block::Air];
    let mut indices = [0u16; 4096];

    for (i, block) in blocks.iter().copied().enumerate() {
        let index = match palette.iter().position(|candidate| *candidate == block) {
            Some(index) => index,
            None => {
                palette.push(block);
                palette.len() - 1
            }
        };
        indices[i] = index as u16;
    }

    let bits = bits_for_palette(palette.len());
    let blocks_per_word = 32 / bits;
    let word_count = (4096 + blocks_per_word - 1) / blocks_per_word;
    let mut data = Vec::with_capacity(3 + word_count * 4 + 512);
    data.push(8);
    data.push(1);
    data.push((bits as u8) << 1);

    for word_index in 0..word_count {
        let mut word = 0u32;
        for slot in 0..blocks_per_word {
            let block_index = word_index * blocks_per_word + slot;
            if block_index >= 4096 {
                break;
            }
            word |= (indices[block_index] as u32) << (slot * bits);
        }
        data.extend_from_slice(&word.to_le_bytes());
    }

    data.extend_from_slice(&(palette.len() as u32).to_le_bytes());
    for block in palette {
        encode_palette_entry(&mut data, block)?;
    }
    Ok(data)
}

fn bits_for_palette(count: usize) -> usize {
    for bits in [1usize, 2, 3, 4, 5, 6, 8, 16] {
        if (1usize << bits) >= count {
            return bits;
        }
    }
    16
}

fn encode_palette_entry(out: &mut Vec<u8>, block: Block) -> Result<(), String> {
    write_compound_start(out, "")?;
    write_string_tag(out, "name", block.name())?;
    write_compound_start(out, "states")?;
    if block == Block::Water {
        write_int_tag(out, "liquid_depth", 0)?;
    }
    write_end(out);
    write_int_tag(out, "version", BLOCK_STATE_VERSION)?;
    write_end(out);
    Ok(())
}

fn encode_data2d(
    cx: i32,
    cz: i32,
    width: usize,
    depth: usize,
    sea_level: i32,
    heights: &[i32],
    materials: &[u8],
    water: &[u8],
) -> Vec<u8> {
    let mut data = Vec::with_capacity(768);
    let mut biomes = [1u8; 256];

    for lz in 0..16usize {
        for lx in 0..16usize {
            let x = cx as isize * 16 + lx as isize;
            let z = cz as isize * 16 + lz as isize;
            let mut top = 1i16;
            let mut biome = 1u8;
            if x >= 0 && z >= 0 && (x as usize) < width && (z as usize) < depth {
                let i = z as usize * width + x as usize;
                let h = heights[i].clamp(4, 120);
                let wet = water[i] != 0 || h < sea_level - 1;
                top = (if wet {
                    sea_level.max(h) + 1
                } else {
                    h + 1
                }) as i16;
                biome = if wet {
                    0
                } else {
                    match materials[i] {
                        1 => 2,
                        2 => 3,
                        _ => 1,
                    }
                };
            }
            data.extend_from_slice(&top.to_le_bytes());
            biomes[lz * 16 + lx] = biome;
        }
    }
    data.extend_from_slice(&biomes);
    data
}

fn chunk_key(cx: i32, cz: i32, tag: u8) -> Vec<u8> {
    let mut key = Vec::with_capacity(9);
    key.extend_from_slice(&cx.to_le_bytes());
    key.extend_from_slice(&cz.to_le_bytes());
    key.push(tag);
    key
}

fn subchunk_key(cx: i32, cz: i32, sy: i8) -> Vec<u8> {
    let mut key = Vec::with_capacity(10);
    key.extend_from_slice(&cx.to_le_bytes());
    key.extend_from_slice(&cz.to_le_bytes());
    key.push(TAG_SUBCHUNK);
    key.push(sy as u8);
    key
}

fn write_level_dat(
    output: &Path,
    world_name: &str,
    spawn_x: i32,
    spawn_y: i32,
    spawn_z: i32,
) -> Result<(), String> {
    let mut nbt = Vec::new();
    write_compound_start(&mut nbt, "")?;
    write_int_tag(&mut nbt, "StorageVersion", 10)?;
    write_int_tag(&mut nbt, "NetworkVersion", 594)?;
    write_string_tag(&mut nbt, "LevelName", world_name)?;
    write_int_tag(&mut nbt, "SpawnX", spawn_x)?;
    write_int_tag(&mut nbt, "SpawnY", spawn_y)?;
    write_int_tag(&mut nbt, "SpawnZ", spawn_z)?;
    write_long_tag(&mut nbt, "RandomSeed", 0)?;
    write_long_tag(&mut nbt, "Time", 6000)?;
    let last_played = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|v| v.as_secs() as i64)
        .unwrap_or(0);
    write_long_tag(&mut nbt, "LastPlayed", last_played)?;
    write_int_tag(&mut nbt, "Generator", 2)?;
    write_int_tag(&mut nbt, "GameType", 1)?;
    write_int_tag(&mut nbt, "Difficulty", 0)?;
    write_byte_tag(&mut nbt, "commandsEnabled", 1)?;
    write_byte_tag(&mut nbt, "hasBeenLoadedInCreative", 1)?;
    write_int_tag(&mut nbt, "PlayerPermissionsLevel", 2)?;
    write_int_tag(&mut nbt, "defaultPlayerPermissions", 2)?;
    write_byte_tag(&mut nbt, "showcoordinates", 1)?;
    write_byte_tag(&mut nbt, "dodaylightcycle", 1)?;
    write_byte_tag(&mut nbt, "doweathercycle", 1)?;
    write_end(&mut nbt);

    let mut file = Vec::with_capacity(nbt.len() + 8);
    file.extend_from_slice(&10u32.to_le_bytes());
    file.extend_from_slice(&(nbt.len() as u32).to_le_bytes());
    file.extend_from_slice(&nbt);
    fs::write(output.join("level.dat"), file)
        .map_err(|e| format!("No se pudo escribir level.dat: {e}"))
}

fn write_compound_start(out: &mut Vec<u8>, name: &str) -> Result<(), String> {
    out.push(10);
    write_name(out, name)
}

fn write_end(out: &mut Vec<u8>) {
    out.push(0);
}

fn write_byte_tag(out: &mut Vec<u8>, name: &str, value: i8) -> Result<(), String> {
    out.push(1);
    write_name(out, name)?;
    out.push(value as u8);
    Ok(())
}

fn write_int_tag(out: &mut Vec<u8>, name: &str, value: i32) -> Result<(), String> {
    out.push(3);
    write_name(out, name)?;
    out.extend_from_slice(&value.to_le_bytes());
    Ok(())
}

fn write_long_tag(out: &mut Vec<u8>, name: &str, value: i64) -> Result<(), String> {
    out.push(4);
    write_name(out, name)?;
    out.extend_from_slice(&value.to_le_bytes());
    Ok(())
}

fn write_string_tag(out: &mut Vec<u8>, name: &str, value: &str) -> Result<(), String> {
    out.push(8);
    write_name(out, name)?;
    write_string_payload(out, value)
}

fn write_name(out: &mut Vec<u8>, name: &str) -> Result<(), String> {
    write_string_payload(out, name)
}

fn write_string_payload(out: &mut Vec<u8>, value: &str) -> Result<(), String> {
    let bytes = value.as_bytes();
    if bytes.len() > u16::MAX as usize {
        return Err("Texto NBT demasiado largo".to_string());
    }
    out.extend_from_slice(&(bytes.len() as u16).to_le_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}

fn compress_error(error: impl std::fmt::Display) -> rusty_leveldb::Status {
    rusty_leveldb::Status {
        code: rusty_leveldb::StatusCode::CompressionError,
        err: error.to_string(),
    }
}

struct NoneCompressor;
impl Compressor for NoneCompressor {
    fn encode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        Ok(block)
    }

    fn decode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        Ok(block)
    }
}

struct BedrockZlibCompressor;
impl Compressor for BedrockZlibCompressor {
    fn encode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&block).map_err(compress_error)?;
        encoder.finish().map_err(compress_error)
    }

    fn decode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        let mut output = Vec::new();
        ZlibDecoder::new(&block[..])
            .read_to_end(&mut output)
            .map_err(compress_error)?;
        Ok(output)
    }
}

struct RawDeflateCompressor;
impl Compressor for RawDeflateCompressor {
    fn encode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        let mut encoder = DeflateEncoder::new(Vec::new(), Compression::fast());
        encoder.write_all(&block).map_err(compress_error)?;
        encoder.finish().map_err(compress_error)
    }

    fn decode(&self, block: Vec<u8>) -> rusty_leveldb::Result<Vec<u8>> {
        let mut output = Vec::new();
        DeflateDecoder::new(&block[..])
            .read_to_end(&mut output)
            .map_err(compress_error)?;
        Ok(output)
    }
}

fn bedrock_compressor_list() -> Rc<CompressorList> {
    let mut list = CompressorList::new();
    list.set_with_id(0, NoneCompressor);
    list.set_with_id(ZLIB_ID, BedrockZlibCompressor);
    list.set_with_id(RAW_DEFLATE_ID, RawDeflateCompressor);
    Rc::new(list)
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_reopenable_bedrock_leveldb() {
        let temp = tempfile::tempdir().expect("tempdir");
        let width = 16usize;
        let depth = 16usize;
        let heights = vec![64i32; width * depth];
        let materials = vec![0u8; width * depth];
        let water = vec![0u8; width * depth];

        export_world(
            temp.path(),
            "WorldPainter test",
            width,
            depth,
            62,
            &heights,
            &materials,
            &water,
        )
        .expect("world export");

        let level_dat = fs::read(temp.path().join("level.dat")).expect("level.dat");
        assert!(level_dat.len() > 8);
        assert_eq!(u32::from_le_bytes(level_dat[0..4].try_into().unwrap()), 10);
        assert!(temp.path().join("levelname.txt").is_file());
        assert!(temp.path().join("db").join("CURRENT").is_file());

        let options = Options {
            create_if_missing: false,
            compressor_list: bedrock_compressor_list(),
            ..Options::default()
        };
        let mut db = DB::open(temp.path().join("db"), options).expect("reopen LevelDB");

        let version = db
            .get(&chunk_key(0, 0, TAG_VERSION))
            .expect("ChunkVersion record");
        assert_eq!(&*version, &[40u8]);

        let finalized = db
            .get(&chunk_key(0, 0, TAG_FINALIZED))
            .expect("FinalizedState record");
        assert_eq!(&*finalized, &2i32.to_le_bytes());

        assert!(
            db.get(&subchunk_key(0, 0, 4))
                .expect("read subchunk key")
                .is_some()
        );
    }
}
