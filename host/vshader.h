/* Direct3D 8 vertex shaders on the CPU (see vshader.c). */
#ifndef RC_VSHADER_H
#define RC_VSHADER_H

#include "d3d8.h"

typedef struct {
    const uint8_t *data[16];   /* per stream: host pointer to vertex 0 */
    uint32_t stride[16];
    const float *ff_mvp;       /* world*view*projection, for declaration-only (fixed-function) shaders */
} VsStreams;

typedef struct { float pos[4], d0[4], d1[4], tex[4][4]; } VsOut;

/* Register file of one shader invocation: temps, inputs, constants, a zero vector, outputs. Kept across
   the vertices of a draw, so the constants are set up once (vs_begin) rather than per vertex. */
enum { VSF_TEMP = 0, VSF_IN = 12, VSF_CONST = 28, VSF_ZERO = 124, VSF_OPOS = 125, VSF_OD = 126, VSF_OT = 128,
       VSF_DUMMY = 136, VSF_N = 137 };
typedef struct {
    struct VsProgram *p;
    const VsStreams *st;
    float f[VSF_N][4];
} VsCtx;

void vs_begin(VsCtx *x, Shader *sh, const float consts[96][4], const VsStreams *st);
void vs_exec(VsCtx *x, uint32_t index, VsOut *out);
void vs_run(Shader *sh, const float consts[96][4], const VsStreams *st, uint32_t index, VsOut *out);
int vs_is_fixed_function(Shader *sh);

/* GPU execution: input registers the shader reads (-1: must run on the CPU), fetching them for one
   vertex (vs_begin first), and the WGSL vertex function (malloc'd; NULL if it can't be translated) */
int vs_gpu_inputs(Shader *sh);
void vs_fetch(VsCtx *x, uint32_t index, float *out);
char *vs_to_wgsl(Shader *sh);
/* the vertex streams a GPU-run shader reads (bit per stream), and its constants for one draw: the
   device's, with the shader's own def/declaration values over them */
uint32_t vs_stream_mask(Shader *sh);
void vs_constants(Shader *sh, const float consts[96][4], float out[96][4]);

#endif
