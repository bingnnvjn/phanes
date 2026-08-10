/* 工单 22：只读 mmap 字体文件（避免 63MB 级 Apple 字体整份拷贝进堆）。 */
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

void *fable_mmap_file(const char *path, size_t *out_len) {
    if (path == NULL || out_len == NULL) {
        return NULL;
    }
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        return NULL;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return NULL;
    }
    size_t len = (size_t)st.st_size;
    void *p = mmap(NULL, len, PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (p == MAP_FAILED) {
        return NULL;
    }
    *out_len = len;
    return p;
}

int fable_munmap(void *ptr, size_t len) {
    if (ptr == NULL) {
        return -1;
    }
    return munmap(ptr, len);
}
