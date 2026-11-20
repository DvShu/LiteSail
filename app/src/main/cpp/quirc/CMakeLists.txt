# quirc v1.2 sources from https://github.com/dlbeer/quirc/tree/v1.2/lib
add_library(litesail_quirc SHARED
    quirc_jni.c
    upstream/decode.c
    upstream/identify.c
    upstream/quirc.c
    upstream/version_db.c
)
target_include_directories(litesail_quirc PRIVATE upstream)
set_target_properties(litesail_quirc PROPERTIES C_VISIBILITY_PRESET hidden)
target_compile_options(litesail_quirc PRIVATE "$<$<NOT:$<CONFIG:Debug>>:-Oz>")
target_link_options(litesail_quirc PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(litesail_quirc PRIVATE m)
