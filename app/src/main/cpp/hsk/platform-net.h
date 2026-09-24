#ifndef _HSK_PLATFORMNET_H
#define _HSK_PLATFORMNET_H

#ifndef _WIN32
#include <arpa/inet.h>
#include <netinet/in.h>
#else
#include <winsock2.h>
#include <ws2tcpip.h>
#endif

#endif
