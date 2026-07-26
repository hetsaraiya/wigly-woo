cmake_minimum_required(VERSION 3.22.1)
project(woojni C)

# Import the prebuilt Go core (.so per ABI, produced by build-core.sh).
add_library(woocore SHARED IMPORTED)
set_target_properties(woocore PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libwoocore.so)

add_library(woojni SHARED woo_jni.c)
target_include_directories(woojni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(woojni woocore log)
