#![allow(non_snake_case)]

mod decrypt;
mod drm;
mod metadata;

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};
use jni::{AttachGuard, EnvUnowned};
use mp4ameta::Tag;
use std::io::Cursor;
use widevine::{Cdm, CdmLicenseRequest};

fn get_env(env: EnvUnowned) -> AttachGuard {
    unsafe { AttachGuard::from_unowned(env.into_raw()) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_createCdm<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
) -> jlong {
    let Some(cdm) = drm::create_cdm() else {
        return 0;
    };
    Box::into_raw(Box::new(cdm)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_releaseCdm<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }

    unsafe {
        drop(Box::from_raw(ptr as *mut Cdm));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_createCdmLicenseRequest<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    cdm_ptr: jlong,
    pssh: JByteArray<'a>,
) -> jlong {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if cdm_ptr == 0 {
        return 0;
    }

    let cdm = unsafe { &*(cdm_ptr as *const Cdm) };

    let Ok(pssh) = env.convert_byte_array(&pssh) else {
        return 0;
    };

    let Some(request) = drm::create_cdm_license_request(cdm, &pssh) else {
        return 0;
    };

    Box::into_raw(Box::new(request)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_releaseCdmLicenseRequest<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    ptr: jlong,
) {
    if ptr == 0 {
        return;
    }

    unsafe {
        drop(Box::from_raw(ptr as *mut CdmLicenseRequest));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_getChallenge<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    request_ptr: jlong,
) -> JByteArray<'a> {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if request_ptr == 0 {
        return JByteArray::null();
    }

    let request = unsafe { &*(request_ptr as *const CdmLicenseRequest) };

    let Some(challenge) = drm::get_challenge(request) else {
        return JByteArray::null();
    };

    let Ok(challenge_array) = env.byte_array_from_slice(&challenge) else {
        return JByteArray::null();
    };

    challenge_array
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_getContentKey<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    request_ptr: jlong,
    license: JByteArray<'a>,
) -> JByteArray<'a> {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if request_ptr == 0 {
        return JByteArray::null();
    }

    let request = unsafe { &*(request_ptr as *const CdmLicenseRequest) };

    let Ok(license) = env.convert_byte_array(&license) else {
        return JByteArray::null();
    };

    let Some(challenge) = drm::get_content_key(request, &license) else {
        return JByteArray::null();
    };

    let Ok(challenge_array) = env.byte_array_from_slice(&challenge) else {
        return JByteArray::null();
    };

    challenge_array
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_decryptM4a<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    key: JByteArray<'a>,
    data: JByteArray<'a>,
) -> JByteArray<'a> {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    let Ok(key) = env.convert_byte_array(&key) else {
        return JByteArray::null();
    };
    let key = match key.try_into() {
        Ok(k) => k,
        Err(_) => return JByteArray::null(),
    };

    let Ok(data) = env.convert_byte_array(&data) else {
        return JByteArray::null();
    };

    let Ok(decrypted_data) = decrypt::decrypt_m4a(&key, data) else {
        return JByteArray::null();
    };

    let Ok(decrypted_array) = env.byte_array_from_slice(&decrypted_data) else {
        return JByteArray::null();
    };

    decrypted_array
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_readM4aTag<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    data: JByteArray<'a>,
) -> jlong {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    let Ok(data) = env.convert_byte_array(&data) else {
        return 0;
    };

    let Some(tag) = metadata::read_m4a_tag(data) else {
        return 0;
    };

    Box::into_raw(Box::new(tag)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_releaseM4aTag<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
) {
    if tag_ptr == 0 {
        return;
    }

    unsafe {
        drop(Box::from_raw(tag_ptr as *mut Tag));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aStringTag<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    ident: jint,
    value: JString<'a>,
) {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    let ident = ident.to_be_bytes();

    let Ok(value) = value.mutf8_chars(env) else {
        return;
    };
    let value = value.to_string();

    metadata::set_m4a_string_tag(tag, ident, value);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aReservedTag<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    ident: jint,
    value: JByteArray<'a>,
) {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    let ident = ident.to_be_bytes();

    let Ok(value) = env.convert_byte_array(&value) else {
        return;
    };

    metadata::set_m4a_reserved_tag(tag, ident, value);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aArtwork<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    data: JByteArray<'a>,
) {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    let Ok(data) = env.convert_byte_array(&data) else {
        return;
    };

    metadata::set_m4a_artwork(tag, &data);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aDisc<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    disc_number: jint,
    total_discs: jint,
) {
    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    metadata::set_m4a_disc(tag, disc_number as u16, total_discs as u16);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aTrack<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    track_number: jint,
    total_tracks: jint,
) {
    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    metadata::set_m4a_track(tag, track_number as u16, total_tracks as u16);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aCompilation<'a>(
    _env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
) {
    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    metadata::set_m4a_compilation(tag);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_setM4aIsrc<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    isrc: JString<'a>,
) {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if tag_ptr == 0 {
        return;
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    let Ok(isrc) = isrc.mutf8_chars(env) else {
        return;
    };
    let isrc = isrc.to_string();

    metadata::set_m4a_isrc(tag, isrc);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_kyant_amdl_engine_RustLib_writeM4aTag<'a>(
    env: EnvUnowned<'a>,
    _class: JClass<'a>,
    tag_ptr: jlong,
    data: JByteArray<'a>,
) -> JByteArray<'a> {
    let mut guard = get_env(env);
    let env = guard.borrow_env_mut();

    if tag_ptr == 0 {
        return JByteArray::null();
    }

    let tag = unsafe { &mut *(tag_ptr as *mut Tag) };

    let Ok(data) = env.convert_byte_array(&data) else {
        return JByteArray::null();
    };
    let mut buffer = Cursor::new(data);

    if !metadata::write_m4a_tag(tag, &mut buffer) {
        return JByteArray::null();
    }

    let Ok(result_array) = env.byte_array_from_slice(buffer.get_ref()) else {
        return JByteArray::null();
    };

    result_array
}
