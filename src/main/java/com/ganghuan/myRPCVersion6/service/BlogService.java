package com.ganghuan.myRPCVersion6.service;


import com.ganghuan.myRPCVersion6.common.Blog;
import com.ganghuan.myRPCVersion6.common.Retryable;

public interface BlogService {
    @Retryable
    Blog getBlogById(Integer id);
}
