use std::io::Cursor;

use mp4ameta::{Data, Fourcc, Img, ImgFmt, Tag};

pub fn read_m4a_tag(data: Vec<u8>) -> Option<Tag> {
    Tag::read_from(&mut Cursor::new(data)).ok()
}

pub fn set_m4a_string_tag(tag: &mut Tag, ident: [u8; 4], data: String) {
    tag.set_data(Fourcc(ident), Data::Utf8(data));
}

pub fn set_m4a_reserved_tag(tag: &mut Tag, ident: [u8; 4], data: Vec<u8>) {
    tag.set_data(Fourcc(ident), Data::Reserved(data));
}

pub fn set_m4a_artwork(tag: &mut Tag, bytes: &[u8]) {
    let fmt = if bytes.starts_with(b"\x89PNG") { ImgFmt::Png } else { ImgFmt::Jpeg };
    tag.set_artwork(Img::new(fmt, bytes.to_vec()));
}

pub fn set_m4a_disc(tag: &mut Tag, disc_number: u16, total_discs: u16) {
    tag.set_disc(disc_number, total_discs);
}

pub fn set_m4a_track(tag: &mut Tag, track_number: u16, total_tracks: u16) {
    tag.set_track(track_number, total_tracks);
}

pub fn set_m4a_compilation(tag: &mut Tag) {
    tag.set_compilation();
}

pub fn set_m4a_isrc(tag: &mut Tag, isrc: String) {
    tag.set_isrc(isrc);
}

pub fn write_m4a_tag(tag: &mut Tag, buffer: &mut Cursor<Vec<u8>>) -> bool {
    tag.write_to(buffer).is_ok()
}
