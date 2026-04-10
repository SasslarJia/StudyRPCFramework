#!/usr/bin/env python3
"""
Maven Class Explorer Tool for Windsurf IDE

This tool reads Maven dependencies from VSCode's Project Manager for Java
and retrieves class files by their full path from dependencies.
"""

import os
import json
import subprocess
import argparse
import tempfile
import shutil
from mcp.server.fastmcp import FastMCP
from pathlib import Path
import xml.etree.ElementTree as ET

mcp = FastMCP("maven-class-explorer")

class MavenClassExplorer:
    def __init__(self, workspace_path=None):
        self.workspace_path = workspace_path or os.getcwd()
        self.vscode_dir = os.path.join(self.workspace_path, '.vscode')
        self.m2_repo = os.path.expanduser('~/.m2/repository')
        self.dependencies = {}
        self.effective_pom = None
        
    def load_vscode_java_config(self):
        """Load Java project configuration from VSCode"""
        settings_file = os.path.join(self.vscode_dir, 'settings.json')
        if os.path.exists(settings_file):
            try:
                with open(settings_file, 'r') as f:
                    settings = json.load(f)
                    java_config = settings.get('java', {})
                    return java_config
            except Exception as e:
                print(f"Error loading VSCode settings: {e}")
        return {}
    
    def load_dependencies(self):
        """Load dependencies from Maven POM file"""
        # First try to parse the POM file directly
        self.parse_pom_file()
        
        # If no dependencies found, try using Maven to get effective POM
        if not self.dependencies:
            self.get_effective_pom()
            self.parse_effective_pom()
            
        # If still no dependencies, try reading from VSCode Java extension
        if not self.dependencies:
            self.read_vscode_classpath()
            
        print(f"Loaded {len(self.dependencies)} dependencies")
        return self.dependencies
    
    def parse_pom_file(self, pom_path=None):
        """Parse Maven pom.xml file to extract dependencies"""
        if pom_path is None:
            pom_path = os.path.join(self.workspace_path, 'pom.xml')
        
        if not os.path.exists(pom_path):
            print(f"POM file not found at {pom_path}")
            return
        
        try:
            # Use Maven's dependency:list goal to get all dependencies
            print(f"Extracting dependencies from {pom_path} using Maven...")
            
            # Run Maven dependency:list and capture the output
            result = subprocess.run(
                ['mvn', 'dependency:list'],
                cwd=os.path.dirname(pom_path),
                capture_output=True,
                text=True
            )
            
            if result.returncode == 0:
                # Parse the dependency list from stdout
                for line in result.stdout.splitlines():
                    line = line.strip()
                    # Maven dependency format: groupId:artifactId:packaging:version:scope
                    if ':' in line and '[INFO]' in line and 'compile' in line:
                        # Extract the dependency coordinates
                        parts = line.split('[INFO]')[1].strip().split(':')
                        if len(parts) >= 4:
                            group_id = parts[0].strip()
                            artifact_id = parts[1].strip()
                            # Skip packaging (usually jar)
                            version = parts[3].strip()
                            
                            key = f"{group_id}:{artifact_id}"
                            jar_path = self._get_jar_path(group_id, artifact_id, version)
                            
                            if jar_path:
                                self.dependencies[key] = {
                                    "groupId": group_id,
                                    "artifactId": artifact_id,
                                    "version": version,
                                    "path": jar_path
                                }
                                print(f"Found dependency: {key} -> {jar_path}")
            else:
                print(f"Maven dependency:list failed: {result.stderr}")
                print("Falling back to effective POM...")
                self.get_effective_pom()
                self.parse_effective_pom()
            
            print(f"Loaded {len(self.dependencies)} dependencies from Maven")
        except Exception as e:
            print(f"Error running Maven: {e}")
            print("Falling back to effective POM...")
            self.get_effective_pom()
            self.parse_effective_pom()
    
    def get_effective_pom(self):
        """Use Maven to get the effective POM with all dependencies resolved"""
        try:
            temp_file = tempfile.NamedTemporaryFile(delete=False, suffix='.xml')
            temp_file.close()
            
            result = subprocess.run(
                ['mvn', 'help:effective-pom', f'-Doutput={temp_file.name}'],
                cwd=self.workspace_path,
                capture_output=True,
                text=True
            )
            
            if result.returncode == 0:
                self.effective_pom = temp_file.name
                print(f"Generated effective POM at {temp_file.name}")
            else:
                print(f"Error generating effective POM: {result.stderr}")
                os.unlink(temp_file.name)
        except Exception as e:
            print(f"Error running Maven: {e}")
    
    def parse_effective_pom(self):
        """Parse the effective POM file to extract dependencies"""
        if not self.effective_pom or not os.path.exists(self.effective_pom):
            return
        
        try:
            tree = ET.parse(self.effective_pom)
            root = tree.getroot()
            
            # Extract namespace
            ns = {'': root.tag.split('}')[0].strip('{')} if '}' in root.tag else {}
            ns_prefix = '{' + next(iter(ns.values())) + '}' if ns else ''
            
            # Extract dependencies
            dependencies_node = root.find(f".//{ns_prefix}dependencies")
            if dependencies_node is not None:
                for dep in dependencies_node.findall(f".//{ns_prefix}dependency"):
                    group_id = dep.find(f"{ns_prefix}groupId").text if dep.find(f"{ns_prefix}groupId") is not None else ""
                    artifact_id = dep.find(f"{ns_prefix}artifactId").text if dep.find(f"{ns_prefix}artifactId") is not None else ""
                    version = dep.find(f"{ns_prefix}version").text if dep.find(f"{ns_prefix}version") is not None else ""
                    
                    if group_id and artifact_id:
                        key = f"{group_id}:{artifact_id}"
                        self.dependencies[key] = {
                            "groupId": group_id,
                            "artifactId": artifact_id,
                            "version": version,
                            "path": self._get_jar_path(group_id, artifact_id, version)
                        }
        except Exception as e:
            print(f"Error parsing effective POM: {e}")
        finally:
            os.unlink(self.effective_pom)
    
    def read_vscode_classpath(self):
        """Read dependencies from VSCode Java extension classpath file"""
        classpath_file = os.path.join(self.vscode_dir, '.classpath')
        if not os.path.exists(classpath_file):
            return
        
        try:
            tree = ET.parse(classpath_file)
            root = tree.getroot()
            
            for entry in root.findall('./classpathentry'):
                kind = entry.get('kind')
                if kind == 'lib':
                    path = entry.get('path')
                    if path and path.startswith('M2_REPO/'):
                        # Parse Maven coordinates from path
                        parts = path.replace('M2_REPO/', '').split('/')
                        if len(parts) >= 3:
                            group_id = '.'.join(parts[:-2])
                            artifact_id = parts[-2]
                            version_jar = parts[-1]
                            version = version_jar.split('.jar')[0].split(artifact_id + '-')[1]
                            
                            key = f"{group_id}:{artifact_id}"
                            self.dependencies[key] = {
                                "groupId": group_id,
                                "artifactId": artifact_id,
                                "version": version,
                                "path": os.path.join(self.m2_repo, *parts)
                            }
        except Exception as e:
            print(f"Error reading VSCode classpath: {e}")
    
    def find_class(self, class_path):
        """Find a class file in dependencies by its full path"""
        if not self.dependencies:
            self.load_dependencies()
            
        class_file = class_path.replace('.', '/') + '.class'
        temp_dir = None
        
        try:
            # Create a temporary directory for extraction
            temp_dir = tempfile.mkdtemp()
            
            # First search in project dependencies
            for dep_key, dep_info in self.dependencies.items():
                jar_path = dep_info.get('path')
                if not jar_path or not os.path.exists(jar_path):
                    continue
                
                # Use jar command to check if the class exists in this JAR
                result = subprocess.run(
                    ['jar', 'tf', jar_path], 
                    capture_output=True, 
                    text=True
                )
                
                jar_contents = result.stdout.splitlines()
                if class_file in jar_contents:
                    print(f"类在项目依赖中找到: {dep_key} ({jar_path})")
                    
                    # Extract the class file to the temporary directory
                    extract_result = subprocess.run(
                        ['jar', 'xf', jar_path, class_file],
                        cwd=temp_dir,
                        capture_output=True
                    )
                    
                    if extract_result.returncode == 0:
                        extracted_path = os.path.join(temp_dir, class_file)
                        if os.path.exists(extracted_path):
                            # Try to decompile the class file
                            decompiled = self._decompile_class(extracted_path, class_path)
                            if decompiled:
                                # Extract Maven coordinates for consistency
                                coords = self._extract_maven_coords(jar_path)
                                return {
                                    "status": "success",
                                    "source": dep_key,
                                    "coordinates": coords,
                                    "jar_path": jar_path,
                                    "content": decompiled
                                }
                            
                            # If decompilation fails, return bytecode
                            bytecode = self._get_bytecode(extracted_path)
                            # Extract Maven coordinates for consistency
                            coords = self._extract_maven_coords(jar_path)
                            return {
                                "status": "success",
                                "source": dep_key,
                                "coordinates": coords,
                                "jar_path": jar_path,
                                "content": bytecode,
                                "note": "类文件已找到但无法反编译为源代码"
                            }
            
            # If not found in project dependencies, return error
            return {
                "status": "error",
                "message": f"类 {class_path} 在项目依赖中未找到"
            }
                
        except Exception as e:
            return {
                "status": "error",
                "message": f"查找类时出错: {str(e)}"
            }
        finally:
            # Clean up temporary directory
            if temp_dir and os.path.exists(temp_dir):
                shutil.rmtree(temp_dir)
    
    def find_in_jar(self, class_path, jar_path):
        """
        Find a class in a specific JAR file
        
        Args:
            class_path (str): Full class path to find
            jar_path (str): Path to the JAR file to search in
            
        Returns:
            dict: Information about the found class or error message
        """
        if not os.path.exists(jar_path):
            return {
                "status": "error",
                "message": f"JAR文件未找到: {jar_path}"
            }
            
        class_file = class_path.replace('.', '/') + '.class'
        temp_dir = None
        
        try:
            # Create a temporary directory for extraction
            temp_dir = tempfile.mkdtemp()
            
            # Use jar command to check if the class exists in this JAR
            result = subprocess.run(
                ['jar', 'tf', jar_path], 
                capture_output=True, 
                text=True
            )
            
            jar_contents = result.stdout.splitlines()
            if class_file in jar_contents:
                print(f"类在JAR文件中找到: {jar_path}")
                
                # Extract the class file to the temporary directory
                extract_result = subprocess.run(
                    ['jar', 'xf', jar_path, class_file],
                    cwd=temp_dir,
                    capture_output=True
                )
                
                if extract_result.returncode == 0:
                    extracted_path = os.path.join(temp_dir, class_file)
                    if os.path.exists(extracted_path):
                        # Try to decompile the class file
                        decompiled = self._decompile_class(extracted_path, class_path)
                        if decompiled:
                            # Extract Maven coordinates from jar_path
                            coords = self._extract_maven_coords(jar_path)
                            return {
                                "status": "success",
                                "source": coords,
                                "jar_path": jar_path,
                                "content": decompiled
                            }
                        
                        # If decompilation fails, return bytecode
                        bytecode = self._get_bytecode(extracted_path)
                        # Extract Maven coordinates from jar_path
                        coords = self._extract_maven_coords(jar_path)
                        return {
                            "status": "success",
                            "source": coords,
                            "jar_path": jar_path,
                            "content": bytecode,
                            "note": "类文件已找到但无法反编译为源代码"
                        }
            
            return {
                "status": "error",
                "message": f"类 {class_path} 在JAR文件中未找到: {jar_path}"
            }
                
        except Exception as e:
            return {
                "status": "error",
                "message": f"查找类在JAR文件中出错: {str(e)}"
            }
        finally:
            # Clean up temporary directory
            if temp_dir and os.path.exists(temp_dir):
                shutil.rmtree(temp_dir)

    def search_all_repository(self, class_path):
        """Search for a class in all Maven repository, not just project dependencies"""
        class_file = class_path.replace('.', '/') + '.class'
        temp_dir = None
        
        try:
            # Create a temporary directory for extraction
            temp_dir = tempfile.mkdtemp()
            
            print(f"正在所有Maven仓库中搜索类 {class_path}...")
            
            # Search for JAR files that might contain the class
            # First, get the package path components
            package_parts = class_path.split('.')
            search_terms = []
            
            # Add search terms based on package structure
            if len(package_parts) >= 3:
                # Add organization and project name as search terms
                search_terms.append(package_parts[0])
                search_terms.append(package_parts[1])
                search_terms.append(package_parts[2])
                
                # If there are more package parts that might indicate module names, add them too
                if len(package_parts) >= 4:
                    search_terms.append(package_parts[3])
            
            # Find potential JAR files in Maven repository
            potential_jars = []
            for term in search_terms:
                find_cmd = f"find {self.m2_repo} -name '*.jar' | grep -i {term}"
                try:
                    result = subprocess.run(
                        find_cmd,
                        shell=True,
                        capture_output=True,
                        text=True
                    )
                    if result.stdout:
                        potential_jars.extend(result.stdout.splitlines())
                except Exception as e:
                    print(f"Error searching for JAR files with term '{term}': {e}")
            
            # Remove duplicates
            potential_jars = list(set(potential_jars))
            print(f"Found {len(potential_jars)} potential JAR files to search")
            
            # Search for the class in each potential JAR
            for jar_path in potential_jars:
                try:
                    # Use jar command to check if the class exists in this JAR
                    result = subprocess.run(
                        ['jar', 'tf', jar_path], 
                        capture_output=True, 
                        text=True
                    )
                    
                    jar_contents = result.stdout.splitlines()
                    if class_file in jar_contents:
                        print(f"类在Maven仓库中找到: {jar_path}")
                        
                        # Extract the class file to the temporary directory
                        extract_result = subprocess.run(
                            ['jar', 'xf', jar_path, class_file],
                            cwd=temp_dir,
                            capture_output=True
                        )
                        
                        if extract_result.returncode == 0:
                            extracted_path = os.path.join(temp_dir, class_file)
                            if os.path.exists(extracted_path):
                                # Try to decompile the class file
                                decompiled = self._decompile_class(extracted_path, class_path)
                                if decompiled:
                                    # Extract Maven coordinates from jar_path
                                    coords = self._extract_maven_coords(jar_path)
                                    return {
                                        "status": "success",
                                        "source": coords,
                                        "jar_path": jar_path,
                                        "content": decompiled
                                    }
                                
                                # If decompilation fails, return bytecode
                                bytecode = self._get_bytecode(extracted_path)
                                # Extract Maven coordinates from jar_path
                                coords = self._extract_maven_coords(jar_path)
                                return {
                                    "status": "success",
                                    "source": coords,
                                    "jar_path": jar_path,
                                    "content": bytecode,
                                    "note": "类文件已找到但无法反编译为源代码"
                                }
                except Exception as e:
                    print(f"Error processing JAR {jar_path}: {e}")
            
            return {
                "status": "error",
                "message": f"类 {class_path} 在任何Maven仓库JAR文件中未找到"
            }
                
        except Exception as e:
            return {
                "status": "error",
                "message": f"查找类时出错: {str(e)}"
            }
        finally:
            # Clean up temporary directory
            if temp_dir and os.path.exists(temp_dir):
                shutil.rmtree(temp_dir)

    def _extract_maven_coords(self, jar_path):
        """Extract Maven coordinates from JAR path"""
        try:
            # Remove m2 repo prefix
            rel_path = jar_path.replace(self.m2_repo, '').lstrip('/')
            parts = rel_path.split('/')
            
            if len(parts) >= 3:
                # Last part is the JAR file
                jar_file = parts[-1]
                # Second to last is the version
                version = parts[-2]
                # Third to last is the artifact ID
                artifact_id = parts[-3]
                # Everything before that is the group ID
                group_id = '.'.join(parts[:-3])
                
                return f"{group_id}:{artifact_id}:{version}"
            
            return jar_path
        except Exception:
            return jar_path
    
    def _get_jar_path(self, group_id, artifact_id, version):
        """Convert Maven coordinates to local repository path"""
        if not version:
            return None
            
        group_path = group_id.replace('.', '/')
        jar_name = f"{artifact_id}-{version}.jar"
        jar_path = os.path.join(self.m2_repo, group_path, artifact_id, version, jar_name)
        
        if os.path.exists(jar_path):
            return jar_path
        return None

    def _decompile_class(self, class_file_path, class_path):
        """Try to decompile a class file to Java source code"""
        # Try using CFR decompiler if available
        try:
            result = subprocess.run(
                ['java', '-jar', os.path.expanduser('~/tools/cfr.jar'), class_file_path],
                capture_output=True,
                text=True
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout
        except:
            pass
            
        # Try using procyon decompiler if available
        try:
            result = subprocess.run(
                ['java', '-jar', os.path.expanduser('~/tools/procyon.jar'), class_file_path],
                capture_output=True,
                text=True
            )
            if result.returncode == 0 and result.stdout:
                return result.stdout
        except:
            pass
        
        # Fallback to javap for bytecode
        return None
    
    def _get_bytecode(self, class_file_path):
        """Get bytecode representation of a class file"""
        try:
            result = subprocess.run(
                ['javap', '-c', '-p', class_file_path],
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                return result.stdout
        except Exception as e:
            return f"Error getting bytecode: {str(e)}"
        
        return "Could not get bytecode"

@
mcp.tool(name = "find_class", description = "当前工作空间中没有相关类信息时，从maven依赖中查找类内容。class_path:类的全路径名，jar_path:JAR包路径，首次查询不传，如果之前通过此工具查找到过类信息，使用上次返回的jar路径")
async def find_class(class_path: str, jar_path: str) -> dict:
    """
    Find a class in a specific JAR file.

    Args:
        class_path (str): Full class path to find
        jar_path (str): Path to specific JAR file to search in
    
    Returns:
        dict: Result containing class content and JAR coordinates
    """
    workspace = os.getcwd()
    explorer = MavenClassExplorer(workspace)
    # If jar-path is provided, first search in that specific JAR
    if jar_path:
        result = explorer.find_in_jar(class_path, jar_path)
        # If not found in the specified JAR, try project dependencies
        if result.get('status') != 'success':
            print(f"类在指定的JAR中未找到，正在项目依赖中搜索...")
            result = explorer.find_class(class_path)
            # If still not found, try all repositories if search-all-repo is specified
            if result.get('status') != 'success':
                print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
                result = explorer.search_all_repository(class_path)
    # Otherwise, search in dependencies first
    else:
        result = explorer.find_class(class_path)
        # If not found in dependencies and search-all-repo is specified, search all repositories
        if result.get('status') != 'success':
            print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
            result = explorer.search_all_repository(class_path)

    return result


# def main():
#     parser = argparse.ArgumentParser(description='Maven Class Explorer for Windsurf IDE')
#     parser.add_argument('--workspace', help='Path to workspace directory')
#     parser.add_argument('--class-path', help='Full class path to find', required=True)
#     parser.add_argument('--jar-path', help='Path to specific JAR file to search in')
#     parser.add_argument('--output', help='Output format (text, json)', default='text')
#     parser.add_argument('--search-all-repo', help='Search all Maven repository even if not in project dependencies', action='store_true')
    
#     args = parser.parse_args()
    
#     # If workspace is not provided, use current directory
#     workspace = args.workspace or os.getcwd()
    
#     explorer = MavenClassExplorer(workspace)
    
#     # If jar-path is provided, first search in that specific JAR
#     if args.jar_path:
#         result = explorer.find_in_jar(args.class_path, args.jar_path)
#         # If not found in the specified JAR, try project dependencies
#         if result.get('status') != 'success':
#             print(f"类在指定的JAR中未找到，正在项目依赖中搜索...")
#             result = explorer.find_class(args.class_path)
#             # If still not found, try all repositories if search-all-repo is specified
#             if result.get('status') != 'success' and args.search_all_repo:
#                 print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
#                 result = explorer.search_all_repository(args.class_path)
#     # Otherwise, search in dependencies first
#     else:
#         result = explorer.find_class(args.class_path)
#         # If not found in dependencies and search-all-repo is specified, search all repositories
#         if result.get('status') != 'success' and args.search_all_repo:
#             print(f"类在项目依赖中未找到，正在所有Maven仓库中搜索...")
#             result = explorer.search_all_repository(args.class_path)
    
#     if args.output == 'json':
#         import json
#         print(json.dumps(result, indent=2))
#     else:
#         if result.get('status') == 'success':
#             print(f"\n类: {args.class_path}")
#             print(f"找到于: {result.get('source')}")
#             print(f"JAR路径: {result.get('jar_path')}")
#             print(f"坐标: {result.get('coordinates')}")
#             print("\n--- 类内容 ---\n")
#             print(result.get('content'))
#             if result.get('note'):
#                 print(f"\n注意: {result.get('note')}")
#         else:
#             print(f"\n错误: {result.get('message')}")

# if __name__ == "__main__":
#     main()
