package com.wiglywoo;

interface IPrivilegedClipboard {
    void destroy() = 16777114;
    String readText() = 1;
}
