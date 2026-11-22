/* Independent, bounded synthetic GPU correctness test. */
#define CL_TARGET_OPENCL_VERSION 120
#include <CL/cl.h>
#include <dlfcn.h>
#include <openssl/evp.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include "pmk-sha1.h"
#define BASE "/data/local/tmp/lambda-crack-bench"
#define N 4800
#define API(n) __typeof__(&n) p_##n=(void *)dlsym(lib,#n);if(!p_##n){fprintf(stderr,"missing %s\n",#n);return 2;}
#define CHECK(x) do {cl_int code=(x);if(code!=CL_SUCCESS){fprintf(stderr,"OpenCL %d line %d\n",code,__LINE__);return 3;}}while(0)
typedef struct {cl_uint ip[5],op[5],u[10],sum[10];} state_t;
static double now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
typedef struct {int id,threads,begin;const unsigned char *passwords;unsigned char *results;} cpu_task;
static void *cpu_work(void *arg) {
  cpu_task *t=arg;
  for(int i=t->begin+t->id;i<N;i+=t->threads)
    lambda_pmk(t->passwords+i*16,15,(const unsigned char *)"LambdaBenchmark",15,t->results+i*32);
  return NULL;
}
int main(int argc,char **argv) {
  int gpu_count=argc>1?atoi(argv[1]):N;
  int cpu_threads=argc>2?atoi(argv[2]):8;
  if((gpu_count!=0&&gpu_count!=256&&gpu_count!=512&&gpu_count!=1024&&gpu_count!=2400&&gpu_count!=N)||(cpu_threads!=4&&cpu_threads!=8))return 64;
  double start=now();void *lib=dlopen("/vendor/lib64/libOpenCL.so",RTLD_NOW|RTLD_LOCAL);
  if(!lib){fprintf(stderr,"%s\n",dlerror());return 2;}
  API(clGetPlatformIDs);API(clGetDeviceIDs);API(clCreateContext);API(clCreateCommandQueue);
  API(clCreateProgramWithSource);API(clBuildProgram);API(clGetProgramBuildInfo);
  API(clCreateKernel);API(clCreateBuffer);API(clSetKernelArg);API(clEnqueueNDRangeKernel);
  API(clFinish);API(clEnqueueReadBuffer);API(clReleaseMemObject);API(clReleaseKernel);
  API(clReleaseProgram);API(clReleaseCommandQueue);API(clReleaseContext);
  cl_platform_id platform;cl_device_id device;cl_int err;
  CHECK(p_clGetPlatformIDs(1,&platform,NULL));CHECK(p_clGetDeviceIDs(platform,CL_DEVICE_TYPE_GPU,1,&device,NULL));
  cl_context ctx=p_clCreateContext(NULL,1,&device,NULL,NULL,&err);CHECK(err);
  cl_command_queue queue=p_clCreateCommandQueue(ctx,device,0,&err);CHECK(err);
  FILE *f=fopen(BASE"/pmk-gpu-probe.cl","rb");if(!f)return 4;
  char source[16384];size_t len=fread(source,1,sizeof(source)-1,f);fclose(f);source[len]=0;
  const char *ptr=source;cl_program program=p_clCreateProgramWithSource(ctx,1,&ptr,&len,&err);CHECK(err);
  err=p_clBuildProgram(program,1,&device,"-cl-std=CL1.2",NULL,NULL);
  if(err){char log[16384]={0};p_clGetProgramBuildInfo(program,device,CL_PROGRAM_BUILD_LOG,sizeof(log)-1,log,NULL);fprintf(stderr,"%s\n",log);return 5;}
  cl_kernel init=p_clCreateKernel(program,"init",&err);CHECK(err);
  cl_kernel loop=p_clCreateKernel(program,"loop",&err);CHECK(err);
  unsigned char passwords[N*16];state_t *states=calloc(N,sizeof(state_t));
  for(int i=0;i<N;i++)snprintf((char *)passwords+i*16,16,"Bench%010d",i);
  cl_mem pw=p_clCreateBuffer(ctx,CL_MEM_READ_ONLY|CL_MEM_COPY_HOST_PTR,sizeof(passwords),passwords,&err);CHECK(err);
  cl_mem mem=p_clCreateBuffer(ctx,CL_MEM_READ_WRITE,N*sizeof(state_t),NULL,&err);CHECK(err);
  cl_uint count=gpu_count;size_t global=(gpu_count+31)/32*32,local=32;
  CHECK(p_clSetKernelArg(init,0,sizeof(pw),&pw));CHECK(p_clSetKernelArg(init,1,sizeof(mem),&mem));CHECK(p_clSetKernelArg(init,2,sizeof(count),&count));
  CHECK(p_clSetKernelArg(loop,0,sizeof(mem),&mem));CHECK(p_clSetKernelArg(loop,1,sizeof(count),&count));
  unsigned char *cpu_results=calloc(N,32);cpu_task tasks[8];pthread_t tids[8];
  double build=now()-start,begin=now();
  if(gpu_count<N)for(int i=0;i<cpu_threads;i++) {
    tasks[i]=(cpu_task){i,cpu_threads,gpu_count,passwords,cpu_results};
    if(pthread_create(tids+i,NULL,cpu_work,tasks+i))return 70;
  }
  if(gpu_count){CHECK(p_clEnqueueNDRangeKernel(queue,init,1,NULL,&global,&local,0,NULL,NULL));CHECK(p_clFinish(queue));}
  for(unsigned remaining=gpu_count?4095:0;remaining;) {
    cl_uint rounds=remaining>64?64:remaining;remaining-=rounds;
    CHECK(p_clSetKernelArg(loop,2,sizeof(rounds),&rounds));
    CHECK(p_clEnqueueNDRangeKernel(queue,loop,1,NULL,&global,&local,0,NULL,NULL));CHECK(p_clFinish(queue));
  }
  if(gpu_count)CHECK(p_clEnqueueReadBuffer(queue,mem,CL_TRUE,0,gpu_count*sizeof(state_t),states,0,NULL,NULL));
  if(gpu_count<N)for(int i=0;i<cpu_threads;i++)pthread_join(tids[i],NULL);
  double seconds=now()-begin;unsigned mismatches=0,checksum=0;
  for(int i=0;i<N;i++) {
    unsigned char ref[32],actual[32];
    if(!PKCS5_PBKDF2_HMAC_SHA1((char *)passwords+i*16,15,(unsigned char *)"LambdaBenchmark",15,4096,32,ref))return 6;
    if(i<gpu_count)for(int j=0;j<32;j++)actual[j]=(unsigned char)(states[i].sum[j/4]>>(24-8*(j%4)));
    else memcpy(actual,cpu_results+i*32,32);
    if(memcmp(ref,actual,32))mismatches++;
    checksum+=actual[0];
  }
  printf("{\"valid\":%s,\"mismatches\":%u,\"pmks\":%d,\"gpu_count\":%d,\"cpu_threads\":%d,\"seconds\":%.6f,\"pmks_per_second\":%.3f,\"build_setup_s\":%.6f,\"checksum\":%u}\n",mismatches?"false":"true",mismatches,N,gpu_count,cpu_threads,seconds,N/seconds,build,checksum);
  p_clReleaseMemObject(pw);p_clReleaseMemObject(mem);p_clReleaseKernel(init);p_clReleaseKernel(loop);
  p_clReleaseProgram(program);p_clReleaseCommandQueue(queue);p_clReleaseContext(ctx);free(states);free(cpu_results);dlclose(lib);
  return mismatches?1:0;
}
