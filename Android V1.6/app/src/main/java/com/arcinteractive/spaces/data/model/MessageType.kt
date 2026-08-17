package com.arcinteractive.spaces.data.model

enum class MessageType(
    val displayName: String,
    val placeholderIconName: String
) {
    Text("Text", "text"),
    Image("Image", "image"),
    Video("Video", "video"),
    Meme("Meme", "meme"),
    Gif("Gif", "gif"),
    Screenshot("Screenshot", "screenshot"),
    File("File", "file")

    ;

    val isPhotosModuleSupported: Boolean
        get() = when (this) {
            Image, Video -> true
            Text, Meme, Gif, Screenshot, File -> false
        }
}
