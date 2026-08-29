use aes::Aes128;
use ctr::cipher::{
    Block, BlockModeDecrypt, InnerIvInit, KeyInit, StreamCipher, StreamCipherCoreWrapper,
};
use std::io;

type Aes128CbcDec<'a> = cbc::Decryptor<&'a Aes128>;
type Aes128CtrCore<'a> = ctr::CtrCore<&'a Aes128, ctr::flavors::Ctr128BE>;
type Aes128Ctr<'a> = StreamCipherCoreWrapper<Aes128CtrCore<'a>>;

const DEFAULT_SONG_DECRYPTION_KEY: [u8; 16] = [
    0x32, 0xb8, 0xad, 0xe1, 0x76, 0x9e, 0x26, 0xb1, 0xff, 0xb8, 0x98, 0x63, 0x52, 0x79, 0x3f, 0xc6,
];

struct Sample {
    offset: usize,
    size: usize,
    duration: u32,
    desc_index: usize,
    iv: [u8; 16],
    subsample_start: usize,
    subsample_count: usize,
    composition_time_offset: i32,
}

struct SongInfo {
    samples: Vec<Sample>,
    subsamples: Vec<(usize, usize)>,
    moov_data: Vec<u8>,
    encryption_info: Vec<Option<EncryptionInfo>>,
}

#[derive(Clone, Copy)]
struct EncryptionInfo {
    scheme_type: [u8; 4],
    crypt_byte_block: u8,
    skip_byte_block: u8,
    per_sample_iv_size: usize,
    constant_iv: [u8; 16],
}

impl Default for EncryptionInfo {
    fn default() -> Self {
        Self {
            scheme_type: *b"cbcs",
            crypt_byte_block: 0,
            skip_byte_block: 0,
            per_sample_iv_size: 0,
            constant_iv: [0; 16],
        }
    }
}

#[derive(Clone, Copy)]
struct BoxRec {
    offset: usize,
    size: usize,
    typ: [u8; 4],
    header_size: usize,
}

fn scan_top_level_boxes(input: &[u8]) -> io::Result<Vec<BoxRec>> {
    let mut boxes = Vec::new();
    let mut offset = 0usize;
    while let Some((typ, box_offset, size, header_size)) = next_box(input, offset, input.len()) {
        boxes.push(BoxRec {
            offset: box_offset,
            size,
            typ,
            header_size,
        });
        offset = box_offset + size;
    }
    if offset != input.len() && input.len().saturating_sub(offset) >= 8 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "decrypt: invalid top-level MP4 box layout",
        ));
    }
    Ok(boxes)
}

fn extract_track_id(moov: &[u8], handler_type: &[u8; 4], default: u32) -> u32 {
    let Some(trak) = find_track_by_handler(moov, handler_type) else {
        return default;
    };
    let Some(tkhd) = find_child_box(trak, b"tkhd", 8) else {
        return default;
    };
    if tkhd.len() < 32 {
        return default;
    }
    if tkhd[8] == 0 {
        be_u32(tkhd, 20).unwrap_or(default)
    } else {
        be_u32(tkhd, 28).unwrap_or(default)
    }
}

fn extract_trex_defaults(moov: &[u8], target_track_id: u32) -> (u32, usize) {
    let mut defaults = (1024, 0usize);
    let Some(mvex) = find_child_box(moov, b"mvex", 8) else {
        return defaults;
    };

    let mut offset = 8usize;
    while let Some((typ, box_offset, size, _)) = next_box(mvex, offset, mvex.len()) {
        if &typ == b"trex" && size >= 32 {
            let trex = &mvex[box_offset..box_offset + size];
            let track_id = be_u32(trex, 12).unwrap_or(0);
            if target_track_id == 0 || track_id == target_track_id {
                defaults.0 = be_u32(trex, 20).unwrap_or(defaults.0);
                defaults.1 = be_u32(trex, 24).unwrap_or(0) as usize;
                return defaults;
            }
        }
        offset = box_offset + size;
    }
    defaults
}

fn parse_tfhd(data: &[u8], info: &mut TfhdInfo) {
    if data.len() < 8 {
        return;
    }
    let flags = ((data[1] as u32) << 16) | ((data[2] as u32) << 8) | data[3] as u32;
    info.track_id = be_u32(data, 4).unwrap_or(0);
    let mut offset = 8usize;

    if flags & 0x01 != 0 && offset + 8 <= data.len() {
        info.base_data_offset = be_u64(data, offset);
        offset += 8;
    }
    if flags & 0x02 != 0 && offset + 4 <= data.len() {
        info.desc_index = be_u32(data, offset).unwrap_or(0) as usize;
        offset += 4;
    }
    if flags & 0x08 != 0 && offset + 4 <= data.len() {
        info.default_duration = be_u32(data, offset).unwrap_or(info.default_duration);
        offset += 4;
    }
    if flags & 0x10 != 0 && offset + 4 <= data.len() {
        info.default_size = be_u32(data, offset).unwrap_or(0) as usize;
        offset += 4;
    }
    if flags & 0x20 != 0 && offset + 4 <= data.len() {}
}

struct TrunEntry {
    duration: Option<u32>,
    size: Option<usize>,
    composition_time_offset: i32,
}

fn parse_trun(data: &[u8]) -> (Vec<TrunEntry>, Option<i32>) {
    let mut entries = Vec::new();
    if data.len() < 8 {
        return (entries, None);
    }

    let version = data[0];
    let flags = ((data[1] as u32) << 16) | ((data[2] as u32) << 8) | data[3] as u32;
    let sample_count = be_u32(data, 4).unwrap_or(0) as usize;
    entries.reserve(sample_count);
    let mut offset = 8usize;

    let mut data_offset = None;
    if flags & 0x01 != 0 && offset + 4 <= data.len() {
        data_offset = be_i32(data, offset);
        offset += 4;
    }
    if flags & 0x04 != 0 && offset + 4 <= data.len() {
        offset += 4;
    }

    for _ in 0..sample_count {
        let mut entry = TrunEntry {
            duration: None,
            size: None,
            composition_time_offset: 0,
        };

        if flags & 0x100 != 0 && offset + 4 <= data.len() {
            entry.duration = be_u32(data, offset);
            offset += 4;
        }
        if flags & 0x200 != 0 && offset + 4 <= data.len() {
            entry.size = be_u32(data, offset).map(|v| v as usize);
            offset += 4;
        }
        if flags & 0x400 != 0 && offset + 4 <= data.len() {
            offset += 4;
        }
        if flags & 0x800 != 0 && offset + 4 <= data.len() {
            entry.composition_time_offset = if version == 1 {
                be_i32(data, offset).unwrap_or(0)
            } else {
                be_u32(data, offset).unwrap_or(0) as i32
            };
            offset += 4;
        }

        entries.push(entry);
    }

    (entries, data_offset)
}

#[derive(Clone, Copy)]
struct SencEntry {
    iv: [u8; 16],
    subsample_start: usize,
    subsample_count: usize,
}

#[derive(Default)]
struct SencInfo {
    entries: Vec<SencEntry>,
    subsamples: Vec<(usize, usize)>,
}

fn parse_senc_strict(
    data: &[u8],
    per_sample_iv_size: usize,
    sample_sizes: &[usize],
) -> Option<SencInfo> {
    if data.len() < 8 {
        return None;
    }
    let flags = ((data[1] as u32) << 16) | ((data[2] as u32) << 8) | data[3] as u32;
    let sample_count = be_u32(data, 4)? as usize;
    if sample_count > sample_sizes.len() {
        return None;
    }

    let mut offset = 8usize;
    let mut info = SencInfo {
        entries: Vec::with_capacity(sample_count),
        subsamples: Vec::new(),
    };

    for sample_index in 0..sample_count {
        let mut iv = [0u8; 16];
        if per_sample_iv_size > 0 {
            if per_sample_iv_size > iv.len() {
                return None;
            }
            iv[..per_sample_iv_size]
                .copy_from_slice(data.get(offset..offset + per_sample_iv_size)?);
            offset += per_sample_iv_size;
        }

        let subsample_start = info.subsamples.len();
        let mut subsample_count = 0usize;
        if flags & 0x02 != 0 {
            let count = be_u16(data, offset)? as usize;
            offset += 2;
            info.subsamples.reserve(count);
            let mut total = 0usize;
            for _ in 0..count {
                let clear = be_u16(data, offset)? as usize;
                let encrypted = be_u32(data, offset + 2)? as usize;
                offset += 6;
                total = total.checked_add(clear)?.checked_add(encrypted)?;
                if total > sample_sizes[sample_index] {
                    return None;
                }
                info.subsamples.push((clear, encrypted));
            }
            subsample_count = count;
        }

        info.entries.push(SencEntry {
            iv,
            subsample_start,
            subsample_count,
        });
    }
    Some(info)
}

fn parse_senc_for_sample_sizes(
    data: &[u8],
    sample_sizes: &[usize],
    preferred_iv_size: usize,
) -> SencInfo {
    let candidates = [preferred_iv_size, 8, 16, 0];
    for (index, &iv_size) in candidates.iter().enumerate() {
        if candidates[..index].contains(&iv_size) {
            continue;
        }
        if let Some(info) = parse_senc_strict(data, iv_size, sample_sizes) {
            return info;
        }
    }
    SencInfo::default()
}

struct TfhdInfo {
    track_id: u32,
    desc_index: usize,
    default_duration: u32,
    default_size: usize,
    base_data_offset: Option<u64>,
}

fn parse_moof_mdat(
    input: &[u8],
    moof_data: &[u8],
    default_duration: u32,
    default_size: usize,
    track_id: u32,
    moof_offset: usize,
    mdat_data_offset: usize,
    encryption_info: &[Option<EncryptionInfo>],
    default_encryption: EncryptionInfo,
    mdat_data_size: usize,
    samples: &mut Vec<Sample>,
    subsamples: &mut Vec<(usize, usize)>,
) {
    let mut offset = 8usize;
    let mut sample_sizes = Vec::new();
    while let Some((typ, traf_offset, traf_size, _)) = next_box(moof_data, offset, moof_data.len()) {
        if &typ != b"traf" {
            offset = traf_offset + traf_size;
            continue;
        }

        let mut info = TfhdInfo {
            track_id: 0,
            desc_index: 0,
            default_duration,
            default_size,
            base_data_offset: None,
        };
        let mut truns: Vec<(Vec<TrunEntry>, Option<i32>)> = Vec::new();
        let mut raw_senc: Option<&[u8]> = None;
        let mut inner = traf_offset + 8;
        let traf_end = traf_offset + traf_size;
        while let Some((inner_type, inner_offset, inner_size, _)) =
            next_box(moof_data, inner, traf_end)
        {
            let payload = &moof_data[inner_offset + 8..inner_offset + inner_size];
            if &inner_type == b"tfhd" {
                parse_tfhd(payload, &mut info);
            } else if &inner_type == b"trun" {
                truns.push(parse_trun(payload));
            } else if &inner_type == b"senc" {
                raw_senc = Some(payload);
            }
            inner = inner_offset + inner_size;
        }
        if info.track_id != track_id {
            offset = traf_offset + traf_size;
            continue;
        }

        let base = info.base_data_offset.unwrap_or(moof_offset as u64);
        let desc_index = info.desc_index.saturating_sub(1);
        let per_sample_iv_size = encryption_info
            .get(desc_index)
            .and_then(Option::as_ref)
            .copied()
            .unwrap_or(default_encryption)
            .per_sample_iv_size;

        let total_entries: usize = truns.iter().map(|(entries, _)| entries.len()).sum();
        samples.reserve(total_entries);
        sample_sizes.clear();
        sample_sizes.reserve(total_entries);
        sample_sizes.extend(
            truns
                .iter()
                .flat_map(|(entries, _)| entries.iter().map(|e| e.size.unwrap_or(info.default_size))),
        );

        let senc_info = raw_senc
            .map(|d| parse_senc_for_sample_sizes(d, &sample_sizes, per_sample_iv_size))
            .unwrap_or_default();
        let senc_subsample_base = subsamples.len();
        subsamples.extend_from_slice(&senc_info.subsamples);

        let mut mdat_pos: Option<i64> = None;
        let mut sample_index = 0usize;

        for (entries, trun_data_offset) in truns {
            if let Some(data_offset) = trun_data_offset {
                mdat_pos = Some(base as i64 + data_offset as i64 - mdat_data_offset as i64);
            } else if mdat_pos.is_none() {
                mdat_pos = Some(0);
            }
            let mut read_offset = mdat_pos.unwrap_or(0).max(0) as usize;

            for entry in entries {
                let sample_size = entry.size.unwrap_or(info.default_size);
                let duration = entry.duration.unwrap_or(info.default_duration);
                let Some(sample_end) = read_offset.checked_add(sample_size) else {
                    sample_index += 1;
                    continue;
                };

                if sample_size > 0 && sample_end <= mdat_data_size {
                    let (iv, subsample_start, subsample_count) =
                        if let Some(senc) = senc_info.entries.get(sample_index) {
                            (
                                senc.iv,
                                senc_subsample_base + senc.subsample_start,
                                senc.subsample_count,
                            )
                        } else {
                            ([0u8; 16], subsamples.len(), 0)
                        };
                    let Some(absolute_start) = mdat_data_offset.checked_add(read_offset) else {
                        sample_index += 1;
                        continue;
                    };
                    let Some(absolute_end) = absolute_start.checked_add(sample_size) else {
                        sample_index += 1;
                        continue;
                    };
                    if absolute_end > input.len() {
                        sample_index += 1;
                        continue;
                    }

                    samples.push(Sample {
                        offset: absolute_start,
                        size: sample_size,
                        duration,
                        desc_index,
                        iv,
                        subsample_start,
                        subsample_count,
                        composition_time_offset: entry.composition_time_offset,
                    });
                    read_offset = sample_end;
                }
                sample_index += 1;
            }
            mdat_pos = Some(read_offset as i64);
        }
        offset = traf_offset + traf_size;
    }
}

fn extract_encryption_info_from_entry(entry: &[u8]) -> Option<EncryptionInfo> {
    if entry.len() < 16 {
        return None;
    }

    let header_size = sample_entry_header_size(&entry[4..8]);
    let sinf = find_child_box(entry, b"sinf", header_size)?;
    let mut info = EncryptionInfo::default();

    if let Some(schm) = find_child_box(sinf, b"schm", 8) {
        if let Some(scheme) = schm.get(12..16) {
            info.scheme_type.copy_from_slice(scheme);
        }
    }

    let schi = find_child_box(sinf, b"schi", 8)?;
    let tenc = find_child_box(schi, b"tenc", 8)?;
    if tenc.len() < 32 {
        return Some(info);
    }

    if tenc[8] > 0 {
        info.crypt_byte_block = tenc[13] >> 4;
        info.skip_byte_block = tenc[13] & 0x0f;
    }
    info.per_sample_iv_size = tenc[15] as usize;

    if info.per_sample_iv_size == 0 && tenc.len() > 32 {
        let iv_size = tenc[32] as usize;
        if iv_size <= 16 && 33 + iv_size <= tenc.len() {
            info.constant_iv[..iv_size].copy_from_slice(&tenc[33..33 + iv_size]);
        }
    }

    Some(info)
}

fn extract_encryption_info_per_stsd(moov: &[u8]) -> Vec<Option<EncryptionInfo>> {
    let Some(trak) = find_track_by_handler(moov, b"soun") else {
        return Vec::new();
    };
    let Some(mdia) = find_child_box(trak, b"mdia", 8) else {
        return Vec::new();
    };
    let Some(minf) = find_child_box(mdia, b"minf", 8) else {
        return Vec::new();
    };
    let Some(stbl) = find_child_box(minf, b"stbl", 8) else {
        return Vec::new();
    };
    let Some(stsd) = find_child_box(stbl, b"stsd", 8) else {
        return Vec::new();
    };
    if stsd.len() < 16 {
        return Vec::new();
    }

    let entry_count = be_u32(stsd, 12).unwrap_or(0) as usize;
    let mut out = Vec::with_capacity(entry_count);
    let mut offset = 16usize;

    for _ in 0..entry_count {
        let Some(entry_size) = be_u32(stsd, offset).map(|v| v as usize) else {
            break;
        };
        if entry_size < 8 || offset + entry_size > stsd.len() {
            break;
        }
        out.push(extract_encryption_info_from_entry(
            &stsd[offset..offset + entry_size],
        ));
        offset += entry_size;
    }

    out
}

fn extract_song(input: &[u8]) -> io::Result<SongInfo> {
    let boxes = scan_top_level_boxes(input)?;
    let moov_box = boxes
        .iter()
        .find(|b| &b.typ == b"moov")
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "decrypt: moov box not found"))?;
    let moov_data = input[moov_box.offset..moov_box.offset + moov_box.size].to_vec();

    let track_id = extract_track_id(&moov_data, b"soun", 0);
    if track_id == 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "decrypt: audio track not found",
        ));
    }

    let (default_duration, default_size) = extract_trex_defaults(&moov_data, track_id);
    let encryption_info = extract_encryption_info_per_stsd(&moov_data);
    let default_encryption = encryption_info
        .iter()
        .flatten()
        .copied()
        .next()
        .unwrap_or_default();

    let mut samples = Vec::new();
    let mut subsamples = Vec::new();
    let mut pending_moof: Option<&BoxRec> = None;
    for b in &boxes {
        if &b.typ == b"moof" {
            pending_moof = Some(b);
        } else if &b.typ == b"mdat" {
            if let Some(moof) = pending_moof.take() {
                let mdat_size = b.size - b.header_size;
                let moof_data = &input[moof.offset..moof.offset + moof.size];
                parse_moof_mdat(
                    input,
                    moof_data,
                    default_duration,
                    default_size,
                    track_id,
                    moof.offset,
                    b.offset + b.header_size,
                    &encryption_info,
                    default_encryption,
                    mdat_size,
                    &mut samples,
                    &mut subsamples,
                );
            }
        }
    }

    if samples.is_empty() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "decrypt: no audio samples found",
        ));
    }

    Ok(SongInfo {
        samples,
        subsamples,
        moov_data,
        encryption_info,
    })
}

fn ctr_from_aes<'a>(aes: &'a Aes128, iv: &[u8; 16]) -> Aes128Ctr<'a> {
    let core = <Aes128CtrCore<'a>>::inner_iv_init(aes, &(*iv).into());
    Aes128Ctr::from_core(core)
}

fn cbc_from_aes<'a>(aes: &'a Aes128, iv: &[u8; 16]) -> Aes128CbcDec<'a> {
    <Aes128CbcDec<'a>>::inner_iv_init(aes, &(*iv).into())
}

fn decrypt_cenc_in_place(
    data: &mut [u8],
    aes: &Aes128,
    iv: &[u8; 16],
    subsamples: &[(usize, usize)],
) -> io::Result<()> {
    let mut cipher = ctr_from_aes(aes, iv);

    if subsamples.is_empty() {
        cipher.apply_keystream(data);
        return Ok(());
    }

    let mut offset = 0usize;
    for &(clear, encrypted) in subsamples {
        offset = offset.checked_add(clear).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: subsample offset overflow",
            )
        })?;
        let end = offset.checked_add(encrypted).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: subsample size overflow",
            )
        })?;
        if end > data.len() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: subsample range exceeds sample size",
            ));
        }
        cipher.apply_keystream(&mut data[offset..end]);
        offset = end;
    }

    Ok(())
}

fn decrypt_cbc_aligned_prefix_in_place(
    data: &mut [u8],
    aes: &Aes128,
    iv: &[u8; 16],
) -> io::Result<()> {
    let aligned_len = data.len() & !0x0f;
    if aligned_len == 0 {
        return Ok(());
    }

    let (blocks, remainder) =
        Block::<Aes128>::slice_as_chunks_mut(&mut data[..aligned_len]);
    debug_assert!(remainder.is_empty());
    let mut decryptor = cbc_from_aes(aes, iv);
    decryptor.decrypt_blocks(blocks);
    Ok(())
}

fn decrypt_cbcs_pattern_in_place(
    data: &mut [u8],
    aes: &Aes128,
    iv: &[u8; 16],
    crypt_blocks: u8,
    skip_blocks: u8,
) -> io::Result<()> {
    if crypt_blocks == 0 {
        return Ok(());
    }

    let crypt_bytes = crypt_blocks as usize * 16;
    let skip_bytes = skip_blocks as usize * 16;
    let mut decryptor = cbc_from_aes(aes, iv);
    let mut offset = 0usize;

    while offset < data.len() {
        let crypt_window = crypt_bytes.min(data.len() - offset);
        let aligned_len = crypt_window & !0x0f;

        if aligned_len > 0 {
            let end = offset + aligned_len;
            let (blocks, remainder) =
                Block::<Aes128>::slice_as_chunks_mut(&mut data[offset..end]);
            debug_assert!(remainder.is_empty());
            decryptor.decrypt_blocks(blocks);
            offset = end;
        }

        offset += crypt_window - aligned_len;
        offset += skip_bytes.min(data.len() - offset);
    }

    Ok(())
}

fn decrypt_cbcs_in_place(
    data: &mut [u8],
    aes: &Aes128,
    iv: &[u8; 16],
    subsamples: &[(usize, usize)],
    crypt_blocks: u8,
    skip_blocks: u8,
    scratch: &mut Vec<u8>,
) -> io::Result<()> {
    if crypt_blocks > 0 && skip_blocks > 0 {
        if subsamples.is_empty() {
            return decrypt_cbcs_pattern_in_place(data, aes, iv, crypt_blocks, skip_blocks);
        }

        let mut offset = 0usize;
        for &(clear, encrypted) in subsamples {
            offset = offset.checked_add(clear).ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "decrypt: subsample offset overflow")
            })?;
            let end = offset.checked_add(encrypted).ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "decrypt: subsample size overflow")
            })?;
            if end > data.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "decrypt: subsample range exceeds sample size",
                ));
            }

            decrypt_cbcs_pattern_in_place(
                &mut data[offset..end],
                aes,
                iv,
                crypt_blocks,
                skip_blocks,
            )?;
            offset = end;
        }
        return Ok(());
    }

    if subsamples.is_empty() {
        return decrypt_cbc_aligned_prefix_in_place(data, aes, iv);
    }

    let encrypted_len = subsamples.iter().try_fold(0usize, |total, &(_, encrypted)| {
        total.checked_add(encrypted).ok_or_else(|| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: encrypted subsample size overflow",
            )
        })
    })?;
    if encrypted_len == 0 {
        return Ok(());
    }


    scratch.clear();
    scratch.reserve(encrypted_len);
    let mut offset = 0usize;
    for &(clear, encrypted_size) in subsamples {
        offset = offset.checked_add(clear).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "decrypt: subsample offset overflow")
        })?;
        let end = offset.checked_add(encrypted_size).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "decrypt: subsample size overflow")
        })?;
        if end > data.len() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: subsample range exceeds sample size",
            ));
        }
        scratch.extend_from_slice(&data[offset..end]);
        offset = end;
    }

    decrypt_cbc_aligned_prefix_in_place(scratch.as_mut_slice(), aes, iv)?;

    let mut source_offset = 0usize;
    let mut sample_offset = 0usize;
    for &(clear, encrypted_size) in subsamples {
        sample_offset += clear;
        let sample_end = sample_offset + encrypted_size;
        let source_end = source_offset + encrypted_size;
        data[sample_offset..sample_end].copy_from_slice(&scratch[source_offset..source_end]);
        sample_offset = sample_end;
        source_offset = source_end;
    }

    Ok(())
}

fn decrypt_sample_in_place(
    data: &mut [u8],
    aes: &Aes128,
    sample: &Sample,
    subsamples: &[(usize, usize)],
    encryption: EncryptionInfo,
    scratch: &mut Vec<u8>,
) -> io::Result<()> {
    if &encryption.scheme_type == b"cenc" {
        return decrypt_cenc_in_place(data, aes, &sample.iv, subsamples);
    }

    let iv = if encryption.per_sample_iv_size == 0 {
        &encryption.constant_iv
    } else {
        &sample.iv
    };
    decrypt_cbcs_in_place(
        data,
        aes,
        iv,
        subsamples,
        encryption.crypt_byte_block,
        encryption.skip_byte_block,
        scratch,
    )
}

fn append_sample_payloads(output: &mut Vec<u8>, input: &[u8], samples: &[Sample]) -> io::Result<()> {
    let mut index = 0usize;
    while index < samples.len() {
        let first = &samples[index];
        let run_start = first.offset;
        let mut run_end = first.offset.checked_add(first.size).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "decrypt: sample range overflow")
        })?;
        if run_end > input.len() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "decrypt: sample range exceeds input",
            ));
        }

        let mut next = index + 1;
        while next < samples.len() && samples[next].offset == run_end {
            run_end = run_end.checked_add(samples[next].size).ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidData, "decrypt: sample range overflow")
            })?;
            if run_end > input.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "decrypt: sample range exceeds input",
                ));
            }
            next += 1;
        }

        output.extend_from_slice(&input[run_start..run_end]);
        index = next;
    }
    Ok(())
}

pub fn decrypt_m4a(key: &[u8; 16], data: Vec<u8>) -> io::Result<Vec<u8>> {
    let SongInfo {
        samples,
        subsamples,
        moov_data,
        encryption_info,
    } = extract_song(&data)?;

    let default_encryption = encryption_info
        .iter()
        .flatten()
        .copied()
        .next()
        .unwrap_or_default();

    let mut sample_info = Vec::with_capacity(samples.len());
    let mut payload_size = 0usize;
    for sample in &samples {
        payload_size = payload_size.checked_add(sample.size).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "mux: payload size overflow")
        })?;
        sample_info.push(SampleInfo {
            size: sample.size as u64,
            duration: sample.duration,
            desc_index: sample.desc_index,
            composition_time_offset: sample.composition_time_offset,
        });
    }

    let track = TrackInfo {
        samples: sample_info,
        moov_data,
    };


    let song_aes = Aes128::new(&DEFAULT_SONG_DECRYPTION_KEY.into());
    let track_aes = Aes128::new(&(*key).into());

    let mut output = build_m4a_prefix(&track, payload_size)?;
    let payload_start = output.len();


    append_sample_payloads(&mut output, &data, &samples)?;
    debug_assert_eq!(output.len(), payload_start + payload_size);

    let mut decrypt_scratch = Vec::new();
    let mut output_offset = payload_start;
    for sample in &samples {
        let end = output_offset.checked_add(sample.size).ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidData, "decrypt: output range overflow")
        })?;


        let sample_aes = match sample.desc_index {
            0 => Some(&song_aes),
            1 => Some(&track_aes),
            _ => None,
        };

        if let Some(sample_aes) = sample_aes {
            let subsample_end = sample
                .subsample_start
                .checked_add(sample.subsample_count)
                .ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "decrypt: subsample range overflow")
                })?;
            if subsample_end > subsamples.len() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "decrypt: subsample range exceeds table",
                ));
            }
            let sample_subsamples = &subsamples[sample.subsample_start..subsample_end];

            let encryption = encryption_info
                .get(sample.desc_index)
                .and_then(Option::as_ref)
                .copied()
                .unwrap_or(default_encryption);
            decrypt_sample_in_place(
                &mut output[output_offset..end],
                sample_aes,
                sample,
                sample_subsamples,
                encryption,
                &mut decrypt_scratch,
            )?;
        }
        output_offset = end;
    }

    Ok(output)
}

struct SampleInfo {
    size: u64,
    duration: u32,
    desc_index: usize,
    composition_time_offset: i32,
}

struct TrackInfo {
    samples: Vec<SampleInfo>,
    moov_data: Vec<u8>,
}

fn be_u16(data: &[u8], offset: usize) -> Option<u16> {
    data.get(offset..offset + 2)
        .map(|b| u16::from_be_bytes([b[0], b[1]]))
}

fn be_i32(data: &[u8], offset: usize) -> Option<i32> {
    data.get(offset..offset + 4)
        .map(|b| i32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn be_u32(data: &[u8], offset: usize) -> Option<u32> {
    data.get(offset..offset + 4)
        .map(|b| u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
}

fn be_u64(data: &[u8], offset: usize) -> Option<u64> {
    data.get(offset..offset + 8)
        .map(|b| u64::from_be_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
}

fn put_u16(out: &mut Vec<u8>, value: u16) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn put_i32(out: &mut Vec<u8>, value: i32) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn put_u32(out: &mut Vec<u8>, value: u32) {
    out.extend_from_slice(&value.to_be_bytes());
}

fn patch_u32(data: &mut [u8], offset: usize, value: u32) {
    if offset + 4 <= data.len() {
        data[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
    }
}

fn patch_u64(data: &mut [u8], offset: usize, value: u64) {
    if offset + 8 <= data.len() {
        data[offset..offset + 8].copy_from_slice(&value.to_be_bytes());
    }
}

fn fourcc(value: &[u8]) -> [u8; 4] {
    [value[0], value[1], value[2], value[3]]
}

fn push_box(out: &mut Vec<u8>, typ: &[u8; 4], content: &[u8]) -> io::Result<()> {
    let size = content
        .len()
        .checked_add(8)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "mp4: box size overflow"))?;
    if size > u32::MAX as usize {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "mp4: 64-bit boxes are not supported by writer",
        ));
    }
    put_u32(out, size as u32);
    out.extend_from_slice(typ);
    out.extend_from_slice(content);
    Ok(())
}

fn push_full_box(
    out: &mut Vec<u8>,
    typ: &[u8; 4],
    version: u8,
    flags: u32,
    content: &[u8],
) -> io::Result<()> {
    let mut full = Vec::with_capacity(content.len() + 4);
    full.push(version);
    full.extend_from_slice(&flags.to_be_bytes()[1..]);
    full.extend_from_slice(content);
    push_box(out, typ, &full)
}

fn wrap_box(typ: &[u8; 4], content: Vec<u8>) -> io::Result<Vec<u8>> {
    let mut out = Vec::new();
    push_box(&mut out, typ, &content)?;
    Ok(out)
}

fn next_box(data: &[u8], offset: usize, end: usize) -> Option<([u8; 4], usize, usize, usize)> {
    if offset + 8 > end {
        return None;
    }
    let raw_size = be_u32(data, offset)?;
    let typ = fourcc(data.get(offset + 4..offset + 8)?);
    let mut header_size = 8usize;
    let size = if raw_size == 1 {
        if offset + 16 > end {
            return None;
        }
        header_size = 16;
        usize::try_from(be_u64(data, offset + 8)?).ok()?
    } else if raw_size == 0 {
        end - offset
    } else {
        raw_size as usize
    };
    if size < header_size || offset + size > end {
        return None;
    }
    Some((typ, offset, size, header_size))
}

fn find_child_box<'a>(
    container: &'a [u8],
    target: &[u8; 4],
    skip_header: usize,
) -> Option<&'a [u8]> {
    let mut offset = skip_header;
    while let Some((typ, box_offset, size, _)) = next_box(container, offset, container.len()) {
        if &typ == target {
            return Some(&container[box_offset..box_offset + size]);
        }
        offset = box_offset + size;
    }
    None
}

fn find_box_offset_recursive(data: &[u8], target: &[u8; 4]) -> Option<usize> {
    fn walk(data: &[u8], target: &[u8; 4], start: usize, end: usize) -> Option<usize> {
        let containers: &[[u8; 4]] = &[
            *b"moov", *b"trak", *b"mdia", *b"minf", *b"stbl", *b"dinf", *b"edts", *b"udta",
            *b"meta",
        ];
        let mut offset = start;
        while let Some((typ, box_offset, size, header_size)) = next_box(data, offset, end) {
            if &typ == target {
                return Some(box_offset);
            }
            if containers.contains(&typ) {
                let mut child_start = box_offset + header_size;
                if &typ == b"meta" {
                    child_start += 4;
                }
                if child_start <= box_offset + size {
                    if let Some(found) = walk(data, target, child_start, box_offset + size) {
                        return Some(found);
                    }
                }
            }
            offset = box_offset + size;
        }
        None
    }
    walk(data, target, 0, data.len())
}

fn find_track_by_handler<'a>(moov_data: &'a [u8], handler_type: &[u8; 4]) -> Option<&'a [u8]> {
    let mut offset = 8usize;
    while let Some((typ, box_offset, size, _)) = next_box(moov_data, offset, moov_data.len()) {
        if &typ == b"trak" {
            let trak = &moov_data[box_offset..box_offset + size];
            if let Some(hdlr_offset) = find_subslice(trak, b"hdlr") {
                let handler_offset = hdlr_offset + 12;
                if handler_offset + 4 <= trak.len()
                    && &trak[handler_offset..handler_offset + 4] == handler_type
                {
                    return Some(trak);
                }
            }
        }
        offset = box_offset + size;
    }
    None
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack
        .windows(needle.len())
        .position(|window| window == needle)
}

fn extract_sample_rate_from_stsd(stsd_content: Option<&[u8]>) -> Option<u32> {
    let data = stsd_content?;
    if data.len() < 44 {
        return None;
    }
    let sample_rate = be_u32(data, 40)? >> 16;
    if (8000..=384000).contains(&sample_rate) {
        Some(sample_rate)
    } else {
        None
    }
}

fn extract_track_timescale(moov: &[u8], default: u32) -> u32 {
    let Some(trak) = find_track_by_handler(moov, b"soun") else {
        return default;
    };
    let Some(mdia) = find_child_box(trak, b"mdia", 8) else {
        return default;
    };
    let Some(mdhd) = find_child_box(mdia, b"mdhd", 8) else {
        return default;
    };
    if mdhd.len() < 28 {
        return default;
    }

    match mdhd[8] {
        0 if mdhd.len() >= 24 => be_u32(mdhd, 20).unwrap_or(default),
        1 if mdhd.len() >= 32 => be_u32(mdhd, 28).unwrap_or(default),
        _ => default,
    }
}

fn sample_entry_header_size(entry_type: &[u8]) -> usize {
    match entry_type {
        b"enca" | b"mp4a" | b"ac-3" | b"ec-3" => 36,
        _ => 36,
    }
}

fn find_original_format(entry_data: &[u8]) -> Option<[u8; 4]> {
    let sinf_idx = find_subslice(entry_data, b"sinf")?;
    if sinf_idx < 4 {
        return None;
    }
    let sinf_size = be_u32(entry_data, sinf_idx - 4)? as usize;
    if sinf_size < 16 || sinf_idx + sinf_size > entry_data.len() + 4 {
        return None;
    }
    let sinf = &entry_data[sinf_idx - 4..sinf_idx - 4 + sinf_size];
    let frma_idx = find_subslice(sinf, b"frma")?;
    if frma_idx < 4 || be_u32(sinf, frma_idx - 4)? != 12 {
        return None;
    }
    Some(fourcc(sinf.get(frma_idx + 4..frma_idx + 8)?))
}

fn remove_sinf_from_entry(entry_data: &[u8]) -> Vec<u8> {
    if entry_data.len() < 16 {
        return entry_data.to_vec();
    }
    let entry_type = &entry_data[4..8];
    let header_size = sample_entry_header_size(entry_type);
    if entry_data.len() < header_size {
        return entry_data.to_vec();
    }
    let mut out = entry_data[..header_size].to_vec();
    let mut child_offset = header_size;
    while let Some((typ, box_offset, size, _)) =
        next_box(entry_data, child_offset, entry_data.len())
    {
        if &typ != b"sinf" {
            out.extend_from_slice(&entry_data[box_offset..box_offset + size]);
        }
        child_offset = box_offset + size;
    }
    let out_len = out.len() as u32;
    patch_u32(&mut out, 0, out_len);
    out
}

fn clean_encrypted_sample_entry(entry_data: &[u8]) -> Vec<u8> {
    if entry_data.len() < 16 {
        return entry_data.to_vec();
    }
    let entry_type = &entry_data[4..8];
    let header_size = sample_entry_header_size(entry_type);
    if entry_data.len() < header_size {
        return entry_data.to_vec();
    }
    let original_format = find_original_format(entry_data).unwrap_or_else(|| match entry_type {
        b"enca" => *b"mp4a",
        _ => fourcc(entry_type),
    });

    let mut out = Vec::new();
    out.extend_from_slice(&entry_data[..4]);
    out.extend_from_slice(&original_format);
    out.extend_from_slice(&entry_data[8..header_size]);
    let mut child_offset = header_size;
    while let Some((typ, box_offset, size, _)) =
        next_box(entry_data, child_offset, entry_data.len())
    {
        if &typ != b"sinf" {
            out.extend_from_slice(&entry_data[box_offset..box_offset + size]);
        }
        child_offset = box_offset + size;
    }
    let out_len = out.len() as u32;
    patch_u32(&mut out, 0, out_len);
    out
}

fn clean_stsd_content(stsd_content: &[u8], preferred_desc_index: Option<usize>) -> Vec<u8> {
    if stsd_content.len() < 8 {
        return stsd_content.to_vec();
    }
    let version_flags = &stsd_content[..4];
    let entry_count = be_u32(stsd_content, 4).unwrap_or(0);
    let mut entries = Vec::new();
    let mut offset = 8usize;
    for _ in 0..entry_count {
        let Some(entry_size) = be_u32(stsd_content, offset).map(|v| v as usize) else {
            break;
        };
        if entry_size < 8 || offset + entry_size > stsd_content.len() {
            break;
        }
        let entry = &stsd_content[offset..offset + entry_size];
        let cleaned = match &entry[4..8] {
            b"enca" => clean_encrypted_sample_entry(entry),
            _ => remove_sinf_from_entry(entry),
        };
        entries.push(cleaned);
        offset += entry_size;
    }
    if let Some(index) = preferred_desc_index {
        if !entries.is_empty() {
            let chosen = entries
                .get(index)
                .cloned()
                .unwrap_or_else(|| entries[0].clone());
            entries = vec![chosen];
        }
    }
    let mut out = Vec::new();
    out.extend_from_slice(version_flags);
    put_u32(&mut out, entries.len() as u32);
    for entry in entries {
        out.extend_from_slice(&entry);
    }
    out
}

fn extract_stsd_content(moov: &[u8], preferred_desc_index: Option<usize>) -> Option<Vec<u8>> {
    let trak = find_track_by_handler(moov, b"soun")?;
    let mdia = find_child_box(trak, b"mdia", 8)?;
    let minf = find_child_box(mdia, b"minf", 8)?;
    let stbl = find_child_box(minf, b"stbl", 8)?;
    let stsd = find_child_box(stbl, b"stsd", 8)?;
    if stsd.len() < 16 {
        return None;
    }
    Some(clean_stsd_content(&stsd[8..], preferred_desc_index))
}

fn preferred_sample_description_index(samples: &[SampleInfo]) -> usize {
    let mut counts: Vec<(usize, usize)> = Vec::new();
    for sample in samples.iter().filter(|s| s.size > 0) {
        if let Some((_, count)) = counts.iter_mut().find(|(idx, _)| *idx == sample.desc_index) {
            *count += 1;
        } else {
            counts.push((sample.desc_index, 1));
        }
    }
    counts
        .into_iter()
        .max_by_key(|(_, count)| *count)
        .map(|(idx, _)| idx)
        .unwrap_or(0)
}

fn patch_mvhd_duration(data: &[u8], duration: u64, timescale: u32) -> Vec<u8> {
    let mut out = data.to_vec();
    if out.len() < 32 {
        return out;
    }
    if out[8] == 0 {
        patch_u32(&mut out, 20, timescale);
        patch_u32(&mut out, 24, duration.min(u32::MAX as u64) as u32);
    } else {
        patch_u32(&mut out, 28, timescale);
        patch_u64(&mut out, 32, duration);
    }
    out
}

fn patch_tkhd_duration(data: &[u8], duration: u64) -> Vec<u8> {
    let mut out = data.to_vec();
    if out.len() < 12 {
        return out;
    }
    out[9..12].copy_from_slice(&7u32.to_be_bytes()[1..]);
    if out[8] == 0 {
        patch_u32(&mut out, 28, duration.min(u32::MAX as u64) as u32);
    } else {
        patch_u64(&mut out, 36, duration);
    }
    out
}

fn patch_mdhd_duration(data: &[u8], duration: u64, timescale: u32) -> Vec<u8> {
    let mut out = data.to_vec();
    if out.len() < 32 {
        return out;
    }
    if out[8] == 0 {
        patch_u32(&mut out, 20, timescale);
        patch_u32(&mut out, 24, duration.min(u32::MAX as u64) as u32);
    } else {
        patch_u32(&mut out, 28, timescale);
        patch_u64(&mut out, 32, duration);
    }
    out
}

fn patch_first_chunk_offset_in_place(trak: &mut [u8], chunk_offset: u64) -> io::Result<()> {
    if let Some(stco_offset) = find_box_offset_recursive(trak, b"stco") {
        let entry_count_offset = stco_offset + 12;
        let first_entry_offset = stco_offset + 16;
        if first_entry_offset + 4 <= trak.len()
            && be_u32(trak, entry_count_offset).unwrap_or(0) > 0
        {
            if chunk_offset > u32::MAX as u64 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "mux: chunk offset too large for stco",
                ));
            }
            patch_u32(trak, first_entry_offset, chunk_offset as u32);
            return Ok(());
        }
    }
    if let Some(co64_offset) = find_box_offset_recursive(trak, b"co64") {
        let entry_count_offset = co64_offset + 12;
        let first_entry_offset = co64_offset + 16;
        if first_entry_offset + 8 <= trak.len()
            && be_u32(trak, entry_count_offset).unwrap_or(0) > 0
        {
            patch_u64(trak, first_entry_offset, chunk_offset);
            return Ok(());
        }
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidData,
        "mux: unable to patch chunk offset",
    ))
}

fn write_stsd(out: &mut Vec<u8>, stsd_content: Option<&[u8]>) -> io::Result<()> {
    let content = stsd_content.filter(|content| !content.is_empty()).ok_or_else(|| {
        io::Error::new(
            io::ErrorKind::InvalidData,
            "mux: source audio sample description is missing",
        )
    })?;
    push_box(out, b"stsd", content)
}

fn write_stts(out: &mut Vec<u8>, samples: &[SampleInfo]) -> io::Result<()> {
    let mut entries: Vec<(u32, u32)> = Vec::new();
    for sample in samples {
        if let Some(last) = entries
            .last_mut()
            .filter(|(_, delta)| *delta == sample.duration)
        {
            last.0 += 1;
        } else {
            entries.push((1, sample.duration));
        }
    }
    let mut content = Vec::new();
    put_u32(&mut content, entries.len() as u32);
    for (count, delta) in entries {
        put_u32(&mut content, count);
        put_u32(&mut content, delta);
    }
    push_full_box(out, b"stts", 0, 0, &content)
}

fn write_ctts(out: &mut Vec<u8>, samples: &[SampleInfo]) -> io::Result<()> {
    if !samples.iter().any(|s| s.composition_time_offset != 0) {
        return Ok(());
    }
    let mut entries: Vec<(u32, i32)> = Vec::new();
    for sample in samples {
        if let Some(last) = entries
            .last_mut()
            .filter(|(_, offset)| *offset == sample.composition_time_offset)
        {
            last.0 += 1;
        } else {
            entries.push((1, sample.composition_time_offset));
        }
    }
    let version = if entries.iter().any(|(_, offset)| *offset < 0) {
        1
    } else {
        0
    };
    let mut content = Vec::new();
    put_u32(&mut content, entries.len() as u32);
    for (count, offset) in entries {
        put_u32(&mut content, count);
        if version == 1 {
            put_i32(&mut content, offset);
        } else {
            put_u32(&mut content, offset as u32);
        }
    }
    push_full_box(out, b"ctts", version, 0, &content)
}

fn build_mvhd(total_duration: u64, timescale: u32) -> io::Result<Vec<u8>> {
    let mut content = Vec::new();
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    put_u32(&mut content, timescale);
    put_u32(&mut content, total_duration.min(u32::MAX as u64) as u32);
    put_u32(&mut content, 0x0001_0000);
    put_u16(&mut content, 0x0100);
    content.extend_from_slice(&[0; 10]);
    for value in [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000] {
        put_u32(&mut content, value);
    }
    content.extend_from_slice(&[0; 24]);
    put_u32(&mut content, 2);
    let mut out = Vec::new();
    push_full_box(&mut out, b"mvhd", 0, 0, &content)?;
    Ok(out)
}

fn build_tkhd(total_duration: u64) -> io::Result<Vec<u8>> {
    let mut content = Vec::new();
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    put_u32(&mut content, 1);
    put_u32(&mut content, 0);
    put_u32(&mut content, total_duration.min(u32::MAX as u64) as u32);
    content.extend_from_slice(&[0; 8]);
    put_u16(&mut content, 0);
    put_u16(&mut content, 0);
    put_u16(&mut content, 0x0100);
    put_u16(&mut content, 0);
    for value in [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000] {
        put_u32(&mut content, value);
    }
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    let mut out = Vec::new();
    push_full_box(&mut out, b"tkhd", 0, 7, &content)?;
    Ok(out)
}

fn build_mdhd(total_duration: u64, timescale: u32) -> io::Result<Vec<u8>> {
    let mut content = Vec::new();
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    put_u32(&mut content, timescale);
    put_u32(&mut content, total_duration.min(u32::MAX as u64) as u32);
    put_u16(&mut content, 0x55c4);
    put_u16(&mut content, 0);
    let mut out = Vec::new();
    push_full_box(&mut out, b"mdhd", 0, 0, &content)?;
    Ok(out)
}

fn build_hdlr() -> io::Result<Vec<u8>> {
    let mut content = Vec::new();
    put_u32(&mut content, 0);
    content.extend_from_slice(b"soun");
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    put_u32(&mut content, 0);
    content.extend_from_slice(b"SoundHandler\0");
    let mut out = Vec::new();
    push_full_box(&mut out, b"hdlr", 0, 0, &content)?;
    Ok(out)
}

fn build_dinf() -> io::Result<Vec<u8>> {
    let mut dref = Vec::new();
    put_u32(&mut dref, 1);
    put_u32(&mut dref, 12);
    dref.extend_from_slice(b"url ");
    put_u32(&mut dref, 1);
    let mut dinf = Vec::new();
    push_full_box(&mut dinf, b"dref", 0, 0, &dref)?;
    wrap_box(b"dinf", dinf)
}

fn build_moov_internal(track: &TrackInfo, stsd_content: Option<Vec<u8>>) -> io::Result<Vec<u8>> {
    let samples = &track.samples;
    let total_duration: u64 = samples.iter().map(|sample| sample.duration as u64).sum();
    let timescale = extract_sample_rate_from_stsd(stsd_content.as_deref())
        .unwrap_or_else(|| extract_track_timescale(&track.moov_data, 44100));

    let orig_mvhd = find_child_box(&track.moov_data, b"mvhd", 8);
    let audio_trak = find_track_by_handler(&track.moov_data, b"soun");

    let (orig_tkhd, orig_mdhd, orig_hdlr, orig_smhd, orig_dinf) = if let Some(trak) = audio_trak {
        let tkhd = find_child_box(trak, b"tkhd", 8);
        if let Some(mdia) = find_child_box(trak, b"mdia", 8) {
            let mdhd = find_child_box(mdia, b"mdhd", 8);
            let hdlr = find_child_box(mdia, b"hdlr", 8);
            if let Some(minf) = find_child_box(mdia, b"minf", 8) {
                (
                    tkhd,
                    mdhd,
                    hdlr,
                    find_child_box(minf, b"smhd", 8),
                    find_child_box(minf, b"dinf", 8),
                )
            } else {
                (tkhd, mdhd, hdlr, None, None)
            }
        } else {
            (tkhd, None, None, None, None)
        }
    } else {
        (None, None, None, None, None)
    };

    let mut moov = Vec::new();
    if let Some(mvhd) = orig_mvhd {
        moov.extend_from_slice(&patch_mvhd_duration(&mvhd, total_duration, timescale));
    } else {
        moov.extend_from_slice(&build_mvhd(total_duration, timescale)?);
    }

    let mut trak = Vec::new();
    if let Some(tkhd) = orig_tkhd {
        trak.extend_from_slice(&patch_tkhd_duration(&tkhd, total_duration));
    } else {
        trak.extend_from_slice(&build_tkhd(total_duration)?);
    }

    let mut mdia = Vec::new();
    if let Some(mdhd) = orig_mdhd {
        mdia.extend_from_slice(&patch_mdhd_duration(&mdhd, total_duration, timescale));
    } else {
        mdia.extend_from_slice(&build_mdhd(total_duration, timescale)?);
    }
    if let Some(hdlr) = orig_hdlr {
        mdia.extend_from_slice(hdlr);
    } else {
        mdia.extend_from_slice(&build_hdlr()?);
    }

    let mut minf = Vec::new();
    if let Some(smhd) = orig_smhd {
        minf.extend_from_slice(smhd);
    } else {
        let mut smhd_content = Vec::new();
        put_u16(&mut smhd_content, 0);
        put_u16(&mut smhd_content, 0);
        push_full_box(&mut minf, b"smhd", 0, 0, &smhd_content)?;
    }
    if let Some(dinf) = orig_dinf {
        minf.extend_from_slice(dinf);
    } else {
        minf.extend_from_slice(&build_dinf()?);
    }

    let mut stbl = Vec::new();
    write_stsd(&mut stbl, stsd_content.as_deref())?;
    write_stts(&mut stbl, samples)?;
    write_ctts(&mut stbl, samples)?;

    let mut stsc = Vec::new();
    put_u32(&mut stsc, 1);
    put_u32(&mut stsc, 1);
    put_u32(&mut stsc, samples.len() as u32);
    put_u32(&mut stsc, 1);
    push_full_box(&mut stbl, b"stsc", 0, 0, &stsc)?;

    let mut stsz = Vec::new();
    put_u32(&mut stsz, 0);
    put_u32(&mut stsz, samples.len() as u32);
    for sample in samples {
        put_u32(&mut stsz, sample.size.min(u32::MAX as u64) as u32);
    }
    push_full_box(&mut stbl, b"stsz", 0, 0, &stsz)?;

    let mut stco = Vec::new();
    put_u32(&mut stco, 1);
    put_u32(&mut stco, 0);
    push_full_box(&mut stbl, b"stco", 0, 0, &stco)?;

    minf.extend_from_slice(&wrap_box(b"stbl", stbl)?);
    mdia.extend_from_slice(&wrap_box(b"minf", minf)?);
    trak.extend_from_slice(&wrap_box(b"mdia", mdia)?);
    moov.extend_from_slice(&wrap_box(b"trak", trak)?);
    wrap_box(b"moov", moov)
}

fn build_decrypted_track_moov(track: &TrackInfo) -> io::Result<Vec<u8>> {
    let preferred_desc_index = preferred_sample_description_index(&track.samples);
    let stsd = extract_stsd_content(&track.moov_data, Some(preferred_desc_index));
    build_moov_internal(track, stsd)
}

fn ftyp_m4a() -> io::Result<Vec<u8>> {
    let mut content = Vec::new();
    content.extend_from_slice(b"M4A ");
    put_u32(&mut content, 0);
    content.extend_from_slice(b"M4A mp42isom\0\0\0\0");
    wrap_box(b"ftyp", content)
}

fn build_m4a_prefix(track: &TrackInfo, payload_size: usize) -> io::Result<Vec<u8>> {
    if payload_size > u32::MAX as usize - 8 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "mux: mdat too large for 32-bit box size",
        ));
    }

    let ftyp = ftyp_m4a()?;
    let mut moov = build_decrypted_track_moov(track)?;
    let mdat_data_offset = ftyp.len() as u64 + moov.len() as u64 + 8;
    patch_moov_first_trak_chunk_offset_in_place(&mut moov, mdat_data_offset)?;

    let capacity = ftyp
        .len()
        .checked_add(moov.len())
        .and_then(|v| v.checked_add(8))
        .and_then(|v| v.checked_add(payload_size))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "mux: output size overflow"))?;

    let mut out = Vec::with_capacity(capacity);
    out.extend_from_slice(&ftyp);
    out.extend_from_slice(&moov);
    put_u32(&mut out, (payload_size + 8) as u32);
    out.extend_from_slice(b"mdat");
    Ok(out)
}

fn patch_moov_first_trak_chunk_offset_in_place(
    moov: &mut [u8],
    offset: u64,
) -> io::Result<()> {
    let mut child_offset = 8usize;
    while let Some((typ, box_offset, size, _)) = next_box(moov, child_offset, moov.len()) {
        if &typ == b"trak" {
            return patch_first_chunk_offset_in_place(
                &mut moov[box_offset..box_offset + size],
                offset,
            );
        }
        child_offset = box_offset + size;
    }
    Err(io::Error::new(
        io::ErrorKind::InvalidData,
        "mux: trak box not found",
    ))
}

