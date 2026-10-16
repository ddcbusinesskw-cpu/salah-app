On-device recitation model files for the «التسميع» (memorization test) feature of the المجدّد app.

- `zipformer_p_arabic_v3.1.int8.onnx` — Quran-Lab zipformer_p-arabic-v3.1, streaming CTC phoneme recognizer (int8 ONNX, sherpa-onnx). sha256 `31755836528da336a6192121cd7bc82cb41752dddb65566fd000b89c8686da6b`
- `zipformer_p_arabic_v3.1.chunk16.int8.onnx` — the same model re-exported for a 0.32 s streaming chunk (decode_chunk_len 32, T 45) instead of 0.48 s, so recited words appear sooner. The int8 weights are copied verbatim from the file above (no retraining, no re-quantisation); only the streaming graph differs. This is a Derivative of zipformer_p-arabic-v3.1 under NPL-1.2. sha256 `c2e924aab9ee07e3a13c4e4b49baeebdb557def85c46cb1c323c5d7654ac55e9`. Newer app versions download this file; the original stays here for older versions.
- `tokens.txt` — symbol table. sha256 `252c10687e442aa9291973065fae19fa39bcd681c4f5612ec496a647e20b43a1`
- `hafs_phon_v1.json.gz` — expected phonetic text per ayah (Hafs), mapped to the app's words (derived from the model's data).

These files are distributed under the **Quran-Lab No-Profit License 1.2 (NPL-1.2)** — see `LICENSE-NPL-1.2.txt`. They are never sold, and no feature they power is placed behind payment, subscription or ads. Automatic tajweed feedback can be wrong and does not replace a qualified teacher.

Upstream: https://huggingface.co/Quran-Lab/zipformer_p-arabic-v3
