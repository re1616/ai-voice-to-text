# JNI bindings
-keep class ai.onnxruntime.** { *; }
-keep class app.aivoicetotext.whisper.WhisperLib { *; }
-keep class app.aivoicetotext.llama.LlamaLib { *; }
-keep interface app.aivoicetotext.llama.PieceCallback { *; }
-keep class * implements app.aivoicetotext.llama.PieceCallback { *; }
-keepclasseswithmembernames class * { native <methods>; }
