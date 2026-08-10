//! SHA-256（工单 22：字体加载 sha256 校验）。
//! 优先 ARMv8 硬件指令（`sha256_shim.c`，运行时 HWCAP_SHA2 检测），
//! 否则回退 sha2 crate 软实现。

pub use sha2::Digest;

extern "C" {
    fn fable_sha256_hw() -> i32;
    fn fable_sha256_blocks(state: *mut u32, data: *const u8, nblocks: usize);
}

const H0: [u32; 8] = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
];

pub fn sha256(data: &[u8]) -> [u8; 32] {
    if unsafe { fable_sha256_hw() } != 0 {
        return sha256_hw(data);
    }
    sha256_soft(data)
}

pub fn sha256_soft(data: &[u8]) -> [u8; 32] {
    use sha2::Sha256;
    let mut hasher = Sha256::new();
    hasher.update(data);
    hasher.finalize().into()
}

fn sha256_hw(data: &[u8]) -> [u8; 32] {
    let mut state = H0;
    let full_blocks = data.len() / 64;
    if full_blocks > 0 {
        unsafe { fable_sha256_blocks(state.as_mut_ptr(), data.as_ptr(), full_blocks) };
    }
    // padding：0x80 + 0 到 ≡56 mod 64 + 8 字节大端位长。
    let mut padded = Vec::with_capacity(((data.len() + 72) / 64) * 64);
    padded.extend_from_slice(&data[full_blocks * 64..]);
    padded.push(0x80);
    while padded.len() % 64 != 56 {
        padded.push(0);
    }
    padded.extend_from_slice(&((data.len() as u64).wrapping_mul(8)).to_be_bytes());
    if !padded.is_empty() {
        unsafe { fable_sha256_blocks(state.as_mut_ptr(), padded.as_ptr(), padded.len() / 64) };
    }
    let mut out = [0u8; 32];
    for (i, w) in state.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&w.to_be_bytes());
    }
    out
}

pub fn hex(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len() * 2);
    for b in data {
        out.push_str(&format!("{b:02x}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_vectors() {
        // NIST 向量
        assert_eq!(
            hex(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            hex(&sha256(b"")),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        let long = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        assert_eq!(
            hex(&sha256(long.as_bytes())),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
    }
}
