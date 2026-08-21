// process_executor.h
#pragma once
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

int winfex_exec_binary(const char *path,
                       const char *const *argv,
                       const char *const *envp,
                       const char *working_dir,
                       int stdin_fd,
                       int stdout_fd,
                       int stderr_fd,
                       int *out_pid);

#ifdef __cplusplus
}
#endif
