//! JNI bindings for `org.swatonly.xmrvault.Native`.
//!
//! Secrets (PINs, the pepper, seeds) cross as `byte[]`, never `String`, so the Kotlin side can
//! zero them after the call; Rust copies land in `Zeroizing` buffers. Errors become Java
//! exceptions; panics are caught at this boundary.

use std::{
  panic::{catch_unwind, AssertUnwindSafe},
  path::PathBuf,
};

use jni::{
  objects::{JByteArray, JClass, JString},
  sys::{jboolean, jbooleanArray, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_6},
  JNIEnv, JavaVM,
};
use zeroize::Zeroizing;

use crate::{
  api,
  keys::{Net, SeedKind},
  Error,
};

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
  crate::hardening::harden();
  JNI_VERSION_1_6
}

fn throw(env: &mut JNIEnv, e: &Error) {
  let class = match e {
    Error::WrongPin => "java/lang/SecurityException",
    Error::Input(_) => "java/lang/IllegalArgumentException",
    _ => "java/lang/IllegalStateException",
  };
  let _ = env.throw_new(class, e.to_string());
}

/// Run `f`, turning errors and panics into exceptions (and `fallback` as the return value).
fn run<R>(env: &mut JNIEnv, fallback: R, f: impl FnOnce(&mut JNIEnv) -> Result<R, Error>) -> R {
  match catch_unwind(AssertUnwindSafe(|| f(env))) {
    Ok(Ok(v)) => v,
    Ok(Err(e)) => {
      throw(env, &e);
      fallback
    }
    Err(_) => {
      throw(env, &Error::Internal("unexpected failure"));
      fallback
    }
  }
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Result<Zeroizing<Vec<u8>>, Error> {
  if a.is_null() {
    return Err(Error::Input("missing input"));
  }
  env.convert_byte_array(a).map(Zeroizing::new).map_err(|_| Error::Internal("jni bytes"))
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String, Error> {
  if s.is_null() {
    return Err(Error::Input("missing input"));
  }
  env.get_string(s).map(Into::into).map_err(|_| Error::Internal("jni string"))
}

fn jstr(env: &mut JNIEnv, s: &str) -> Result<jstring, Error> {
  env.new_string(s).map(|s| s.into_raw()).map_err(|_| Error::Internal("jni string"))
}

fn jbytes(env: &mut JNIEnv, b: &[u8]) -> Result<jbyteArray, Error> {
  env.byte_array_from_slice(b).map(|a| a.into_raw()).map_err(|_| Error::Internal("jni bytes"))
}

fn jbool(b: bool) -> jboolean {
  if b {
    JNI_TRUE
  } else {
    JNI_FALSE
  }
}

macro_rules! jfn {
  ($name:ident ( $env:ident $(, $arg:ident : $ty:ty)* ) -> $ret:ty = $fallback:expr; $body:block) => {
    #[no_mangle]
    pub extern "system" fn $name(mut env: JNIEnv, _class: JClass $(, $arg: $ty)*) -> $ret {
      run(&mut env, $fallback, |$env| $body)
    }
  };
}

const NULL: jstring = std::ptr::null_mut();

jfn!(Java_org_swatonly_xmrvault_Native_init(env, dir: JString) -> () = (); {
  let dir = string(env, &dir)?;
  api::init(PathBuf::from(dir))
});

jfn!(Java_org_swatonly_xmrvault_Native_exists(env) -> jboolean = JNI_FALSE; {
  let _ = env;
  Ok(jbool(api::exists()))
});

jfn!(Java_org_swatonly_xmrvault_Native_create(env, pin: JByteArray, pepper: JByteArray, net: jint, legacy: jboolean) -> jbyteArray = NULL; {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  let kind = if legacy == JNI_TRUE { SeedKind::Legacy } else { SeedKind::Polyseed };
  let seed = api::create(&pin, &pepper, Net::from_code(net)?, kind)?;
  jbytes(env, seed.as_bytes())
});

jfn!(Java_org_swatonly_xmrvault_Native_restore(env, pin: JByteArray, pepper: JByteArray, net: jint, phrase: JByteArray, height: jlong) -> () = (); {
  let (pin, pepper, phrase) = (bytes(env, &pin)?, bytes(env, &pepper)?, bytes(env, &phrase)?);
  let phrase = std::str::from_utf8(&phrase).map_err(|_| Error::Input("seed must be text"))?;
  let height = u64::try_from(height).ok();
  api::restore(&pin, &pepper, Net::from_code(net)?, phrase, height)
});

jfn!(Java_org_swatonly_xmrvault_Native_unlock(env, pin: JByteArray, pepper: JByteArray) -> jint = -1; {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  Ok(match api::unlock(&pin, &pepper)? {
    api::UnlockResult::Unlocked => 0,
    api::UnlockResult::WrongPin => 1,
    api::UnlockResult::Duress => 2,
  })
});

jfn!(Java_org_swatonly_xmrvault_Native_lock(env) -> () = (); {
  let _ = env;
  api::lock();
  Ok(())
});

jfn!(Java_org_swatonly_xmrvault_Native_isUnlocked(env) -> jboolean = JNI_FALSE; {
  let _ = env;
  Ok(jbool(api::is_unlocked()))
});

jfn!(Java_org_swatonly_xmrvault_Native_status(env) -> jstring = NULL; {
  let s = api::status()?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_setNode(env, json: JString) -> () = (); {
  let json = string(env, &json)?;
  api::set_node(&json)
});

jfn!(Java_org_swatonly_xmrvault_Native_node(env) -> jstring = NULL; {
  let s = api::node()?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_testNode(env) -> jlong = -1; {
  let _ = env;
  Ok(api::test_node()? as jlong)
});

jfn!(Java_org_swatonly_xmrvault_Native_syncStep(env) -> jstring = NULL; {
  let s = api::sync_step()?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_address(env, index: jint) -> jstring = NULL; {
  let s = api::address(u32::try_from(index).map_err(|_| Error::Input("bad index"))?)?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_newSubaddress(env) -> jstring = NULL; {
  let s = api::new_subaddress()?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_validateAddress(env, address: JString) -> jboolean = JNI_FALSE; {
  let a = string(env, &address)?;
  Ok(jbool(api::validate_address(&a)?))
});

jfn!(Java_org_swatonly_xmrvault_Native_prepareSend(env, dest: JString, amount: jlong, priority: jint) -> jstring = NULL; {
  let dest = string(env, &dest)?;
  // Kotlin longs are signed; amounts are u64 bit patterns.
  let s = api::prepare_send(&dest, amount as u64, priority)?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_confirmSend(env, pin: JByteArray, pepper: JByteArray) -> jstring = NULL; {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  let txid = api::confirm_send(&pin, &pepper)?;
  jstr(env, &txid)
});

jfn!(Java_org_swatonly_xmrvault_Native_cancelSend(env) -> () = (); {
  let _ = env;
  api::cancel_send();
  Ok(())
});

jfn!(Java_org_swatonly_xmrvault_Native_history(env) -> jstring = NULL; {
  let s = api::history()?;
  jstr(env, &s)
});

jfn!(Java_org_swatonly_xmrvault_Native_revealSeed(env, pin: JByteArray, pepper: JByteArray) -> jbyteArray = NULL; {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  let seed = api::reveal_seed(&pin, &pepper)?;
  jbytes(env, seed.as_bytes())
});

jfn!(Java_org_swatonly_xmrvault_Native_verifyPin(env, pin: JByteArray, pepper: JByteArray) -> jboolean = JNI_FALSE; {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  Ok(jbool(api::verify_pin(&pin, &pepper)?))
});

jfn!(Java_org_swatonly_xmrvault_Native_changePin(env, old: JByteArray, new: JByteArray, pepper: JByteArray) -> () = (); {
  let (old, new, pepper) = (bytes(env, &old)?, bytes(env, &new)?, bytes(env, &pepper)?);
  api::change_pin(&old, &new, &pepper)
});

jfn!(Java_org_swatonly_xmrvault_Native_setDuressPin(env, pin: JByteArray, duress: JByteArray, pepper: JByteArray) -> () = (); {
  let (pin, pepper) = (bytes(env, &pin)?, bytes(env, &pepper)?);
  let duress = if duress.is_null() { None } else { Some(bytes(env, &duress)?) };
  api::set_duress_pin(&pin, duress.as_ref().map(|d| d.as_slice()), &pepper)
});

jfn!(Java_org_swatonly_xmrvault_Native_rescan(env, height: jlong) -> () = (); {
  let _ = env;
  api::rescan(u64::try_from(height).ok())
});

jfn!(Java_org_swatonly_xmrvault_Native_wipe(env) -> () = (); {
  let _ = env;
  api::wipe()
});

jfn!(Java_org_swatonly_xmrvault_Native_qr(env, text: JString) -> jbooleanArray = std::ptr::null_mut(); {
  let text = string(env, &text)?;
  let (_, modules) = api::qr(&text)?;
  let arr = env.new_boolean_array(modules.len() as i32).map_err(|_| Error::Internal("jni"))?;
  let raw: Vec<jboolean> = modules.into_iter().map(jbool).collect();
  env.set_boolean_array_region(&arr, 0, &raw).map_err(|_| Error::Internal("jni"))?;
  Ok(arr.into_raw())
});

jfn!(Java_org_swatonly_xmrvault_Native_securityReport(env) -> jstring = NULL; {
  let r = crate::hardening::report().to_string();
  jstr(env, &r)
});
