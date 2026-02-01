# 08 - MCP工具暴露机制

## 概述

这是整个MCP工具最关键的部分——如何将Python函数暴露给AI模型调用。

## @mcp.tool 装饰器

```python
@mcp.tool(
    name="find_class", 
    description="当前工作空间中没有相关类信息时，从maven依赖中查找类内容。class_path:类的全路径名，jar_path:JAR包路径，首次查询不传，如果之前通过此工具查找到过类信息，使用上次返回的jar路径"
)
async def find_class(class_path: str, jar_path: str) -> dict:
    """
    Find a class in a specific JAR file.
    """
    # 实现...
```

## 装饰器参数详解

| 参数 | 类型 | 说明 |
|------|------|------|
| `name` | str | 工具的唯一标识符，AI模型通过这个名称调用工具 |
| `description` | str | 工具的描述，告诉AI模型这个工具能做什么、什么时候该用 |

## 函数签名的重要性

```python
async def find_class(class_path: str, jar_path: str) -> dict:
```

### 1. async 关键字

```python
async def find_class(...):  # 异步函数
```

**为什么使用异步？**
- MCP协议基于异步I/O
- 允许同时处理多个请求
- 适合网络和文件I/O操作

### 2. 类型注解

```python
class_path: str    # 参数类型
jar_path: str      # 参数类型
-> dict            # 返回类型
```

**类型注解的作用：**
- FastMCP 自动生成参数验证
- AI模型知道需要传递什么类型的参数
- IDE提供更好的代码补全

### 3. 参数自动转换为工具Schema

FastMCP 会将函数签名转换为 JSON Schema：

```json
{
  "name": "find_class",
  "description": "当前工作空间中没有相关类信息时...",
  "parameters": {
    "type": "object",
    "properties": {
      "class_path": {
        "type": "string",
        "description": "Full class path to find"
      },
      "jar_path": {
        "type": "string", 
        "description": "Path to specific JAR file to search in"
      }
    },
    "required": ["class_path", "jar_path"]
  }
}
```

## 工具调用流程

```
┌─────────────────────────────────────────────────────────────┐
│                       AI Model                              │
│  "我需要了解 org.springframework.core.SpringVersion 类"      │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ 决定调用工具
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    MCP Request                              │
│  {                                                          │
│    "method": "tools/call",                                  │
│    "params": {                                              │
│      "name": "find_class",                                  │
│      "arguments": {                                         │
│        "class_path": "org.springframework.core.SpringVersion",│
│        "jar_path": ""                                       │
│      }                                                      │
│    }                                                        │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ JSON-RPC
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                   FastMCP Server                            │
│                                                             │
│  1. 解析请求，找到 "find_class" 工具                         │
│  2. 验证参数类型                                             │
│  3. 调用 find_class(class_path, jar_path)                   │
│  4. 等待异步执行完成                                         │
│  5. 将结果包装为响应                                         │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    MCP Response                             │
│  {                                                          │
│    "content": [                                             │
│      {                                                      │
│        "type": "text",                                      │
│        "text": "{\"status\": \"success\", ...}"            │
│      }                                                      │
│    ]                                                        │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                       AI Model                              │
│  "根据返回的源代码，SpringVersion类有一个getVersion方法..."   │
└─────────────────────────────────────────────────────────────┘
```

## 工具函数实现详解

```python
@mcp.tool(name="find_class", description="...")
async def find_class(class_path: str, jar_path: str) -> dict:
    # 1. 获取当前工作目录
    workspace = os.getcwd()
    
    # 2. 创建探索器实例
    explorer = MavenClassExplorer(workspace)
    
    # 3. 根据是否提供jar_path决定搜索策略
    if jar_path:
        # 优先在指定JAR中搜索
        result = explorer.find_in_jar(class_path, jar_path)
        
        # 如果没找到，扩大搜索范围
        if result.get('status') != 'success':
            print(f"类在指定的JAR中未找到，正在项目依赖中搜索...")
            result = explorer.find_class(class_path)
            
            # 最后尝试全局搜索
            if result.get('status') != 'success':
                print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
                result = explorer.search_all_repository(class_path)
    else:
        # 没有指定JAR，从项目依赖开始搜索
        result = explorer.find_class(class_path)
        
        if result.get('status') != 'success':
            print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
            result = explorer.search_all_repository(class_path)

    return result
```

## 搜索策略图示

```
                    find_class 被调用
                           │
                           ▼
                   jar_path 是否提供?
                           │
              ┌────────────┴────────────┐
              │是                       │否
              ▼                         ▼
    ┌──────────────────┐      ┌──────────────────┐
    │ find_in_jar()    │      │ find_class()     │
    │ 在指定JAR中搜索   │      │ 在项目依赖中搜索  │
    └──────────────────┘      └──────────────────┘
              │                         │
          找到？                     找到？
              │                         │
    ┌─────────┴─────────┐     ┌─────────┴─────────┐
    │是      │否        │     │是      │否        │
    ▼        ▼          │     ▼        ▼          │
  返回    find_class()  │   返回   search_all_   │
  结果    项目依赖搜索   │   结果   repository()  │
              │                         │
          找到？                        │
              │                         │
    ┌─────────┴─────────┐               │
    │是      │否        │               │
    ▼        ▼          │               │
  返回    search_all_   │               │
  结果    repository()  │               │
              │         │               │
              └─────────┴───────────────┘
                        │
                    找到？
                        │
              ┌─────────┴─────────┐
              │是                 │否
              ▼                   ▼
           返回结果           返回错误
```

## description 的重要性

description 是告诉AI模型**什么时候**该使用这个工具：

```python
description = """
当前工作空间中没有相关类信息时，从maven依赖中查找类内容。
class_path: 类的全路径名，
jar_path: JAR包路径，首次查询不传，
         如果之前通过此工具查找到过类信息，使用上次返回的jar路径
"""
```

**好的description应该包含：**
1. 工具的用途（查找类内容）
2. 使用场景（工作空间中没有相关类信息时）
3. 参数说明（各参数的含义）
4. 使用技巧（jar_path的复用策略）

## 下一步

在最后一章节中，我们将总结完整的工作流程和关键知识点。
