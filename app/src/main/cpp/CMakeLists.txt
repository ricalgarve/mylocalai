cmake_minimum_required(VERSION 3.22.1)

project("llama-jni")

add_library(
    llama-jni
    SHARED
    llama-jni.cpp
)

target_include_directories(llama-jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    llama-jni
    ${log-lib}
    ${android-lib}
    dl
)
