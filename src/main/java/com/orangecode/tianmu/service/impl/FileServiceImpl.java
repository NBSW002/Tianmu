package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.mapper.FileMapper;
import com.orangecode.tianmu.model.entity.File;
import com.orangecode.tianmu.service.FileService;
import org.springframework.stereotype.Service;

@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {}
