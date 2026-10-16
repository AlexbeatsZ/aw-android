// Promise wrapper around the native bridge (DashboardBridge.kt, bound as window.AWNative).
// Native work runs off the UI thread; results come back through AWBridge._resolve.
(function () {
  'use strict';
  var pending = Object.create(null);
  var seq = 0;

  function call(method, arg) {
    return new Promise(function (resolve, reject) {
      if (!window.AWNative || typeof window.AWNative.request !== 'function') {
        reject(new Error('native bridge unavailable'));
        return;
      }
      var id = 'r' + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        window.AWNative.request(id, method, arg === undefined ? '' : JSON.stringify(arg));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  function _resolve(id, ok, payload) {
    var entry = pending[id];
    if (!entry) return;
    delete pending[id];
    var value;
    try { value = payload ? JSON.parse(payload) : null; } catch (e) { value = payload; }
    if (ok) {
      entry.resolve(value);
    } else {
      var err = new Error((value && value.message) || 'error');
      err.code = value && value.code;
      entry.reject(err);
    }
  }

  window.AWBridge = { call: call, _resolve: _resolve };
})();
