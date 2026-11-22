/* Read-only OpenCL capability probe: no kernels or GPU memory allocations. */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
typedef int32_t cl_int;
typedef uint32_t cl_uint;
typedef uint64_t cl_ulong;
typedef void *cl_platform_id;
typedef void *cl_device_id;
static void string(const char *s) {
    putchar('"');
    for (; *s; s++) { unsigned char c=*s; if (c=='"'||c=='\\') putchar('\\'); if(c>=32)putchar(c); }
    putchar('"');
}
int main(void) {
    alarm(8);
    void *lib=dlopen("/vendor/lib64/libOpenCL.so",RTLD_NOW|RTLD_LOCAL);
    if (!lib) { printf("{\"available\":false,\"reason\":");string(dlerror());puts("}");return 0; }
    cl_int (*platforms)(cl_uint,cl_platform_id*,cl_uint*)=dlsym(lib,"clGetPlatformIDs");
    cl_int (*devices)(cl_platform_id,cl_ulong,cl_uint,cl_device_id*,cl_uint*)=dlsym(lib,"clGetDeviceIDs");
    cl_int (*info)(cl_device_id,cl_uint,size_t,void*,size_t*)=dlsym(lib,"clGetDeviceInfo");
    if(!platforms||!devices||!info){puts("{\"available\":false,\"reason\":\"missing_OpenCL_symbols\"}");return 0;}
    cl_uint count=0;cl_platform_id p[8];cl_int status=platforms(8,p,&count);
    if(status||!count){printf("{\"available\":false,\"reason\":\"no_platform\",\"code\":%d}\n",status);return 0;}
    puts("{\"available\":true,\"devices\":[");int comma=0;
    for(cl_uint i=0;i<count&&i<8;i++){
        cl_device_id d[8];cl_uint n=0;if(devices(p[i],0xffffffffULL,8,d,&n))continue;
        for(cl_uint j=0;j<n&&j<8;j++){
            char name[512]={0},version[512]={0},driver[512]={0};cl_ulong memory=0,allocation=0,type=0;size_t group=0;cl_uint units=0;
            info(d[j],0x102b,sizeof(name)-1,name,NULL);info(d[j],0x102f,sizeof(version)-1,version,NULL);
            info(d[j],0x102d,sizeof(driver)-1,driver,NULL);info(d[j],0x101f,sizeof(memory),&memory,NULL);info(d[j],0x1010,sizeof(allocation),&allocation,NULL);
            info(d[j],0x1000,sizeof(type),&type,NULL);info(d[j],0x1004,sizeof(group),&group,NULL);info(d[j],0x1002,sizeof(units),&units,NULL);
            if(comma++)putchar(',');printf("{\"name\":");string(name);printf(",\"version\":");string(version);printf(",\"driver\":");string(driver);
            printf(",\"global_bytes\":%llu,\"max_allocation_bytes\":%llu,\"type\":%llu,\"max_workgroup\":%zu,\"compute_units\":%u}",(unsigned long long)memory,(unsigned long long)allocation,(unsigned long long)type,group,units);
        }
    }
    puts("]}");return 0;
}
