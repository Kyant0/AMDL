use std::io::Cursor;
use widevine::{Cdm, CdmLicenseRequest, Device, KeyType};

pub fn create_cdm() -> Option<Cdm> {
    let wvd = include_bytes!("test.wvd");
    let Ok(device) = Device::read_wvd(Cursor::new(wvd)) else {
        return None;
    };
    let cdm = Cdm::new(device);
    Some(cdm)
}

pub fn create_cdm_license_request(cdm: &Cdm, pssh: &[u8]) -> Option<CdmLicenseRequest> {
    let Ok(pssh) = widevine::Pssh::from_bytes(pssh) else {
        return None;
    };
    let Ok(request) = cdm
        .open()
        .get_license_request(pssh, widevine::LicenseType::STREAMING)
    else {
        return None;
    };
    Some(request)
}

pub fn get_challenge(request: &CdmLicenseRequest) -> Option<Vec<u8>> {
    let Ok(challenge) = request.challenge() else {
        return None;
    };
    Some(challenge)
}

pub fn get_content_key(request: &CdmLicenseRequest, license: &[u8]) -> Option<Vec<u8>> {
    let Ok(keys) = request.get_keys(&license) else {
        return None;
    };
    let Ok(key) = keys.first_of_type(KeyType::CONTENT) else {
        return None;
    };
    Some(key.key.clone())
}
