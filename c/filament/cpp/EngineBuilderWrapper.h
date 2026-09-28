#ifndef FILAMENT_C_ENGINE_BUILDER_WRAPPER_H
#define FILAMENT_C_ENGINE_BUILDER_WRAPPER_H

#include <filament/Engine.h>

// What a FilaEngineBuilder* points to; shared by Engine.cpp and Interop.cpp.
struct FilaEngineBuilderWrapper {
    filament::Engine::Builder builder;
    filament::Engine::Config config;
};

#endif
