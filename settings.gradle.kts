rootDir.listFiles().filter{it.isDirectory && it.name.startsWith("netty-")}.forEach{
    include(":${it.name}")
}
