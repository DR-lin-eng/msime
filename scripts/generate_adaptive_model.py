#!/usr/bin/env python3
"""用标准库生成个人排序的固定 ONNX 特征网络，不下载模型或训练语料。"""
from pathlib import Path
import struct

def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out) + bytes([value])

def integer(field, value):
    return varint(field << 3) + varint(value)

def blob(field, value):
    if isinstance(value, str):
        value = value.encode()
    return varint((field << 3) | 2) + varint(len(value)) + value

def mix(value):
    value ^= value >> 16
    value = value * 0x7FEB352D & 0xFFFFFFFF
    value ^= value >> 15
    value = value * 0x846CA68B & 0xFFFFFFFF
    return value ^ (value >> 16)

def info(name, dimensions):
    shape = b''.join(blob(1, integer(1, dimension)) for dimension in dimensions)
    tensor = integer(1, 1) + blob(2, shape)
    return blob(1, name) + blob(2, blob(1, tensor))

def model():
    data = b''.join(struct.pack('<f', (mix(index + 1) % 2001 - 1000) / 2048) for index in range(128 * 64))
    weights = integer(1, 128) + integer(1, 64) + integer(2, 1) + blob(8, 'weights') + blob(9, data)
    matmul = blob(1, 'features') + blob(1, 'weights') + blob(2, 'projection') + blob(4, 'MatMul')
    tanh = blob(1, 'projection') + blob(2, 'embedding') + blob(4, 'Tanh')
    graph = blob(1, matmul) + blob(1, tanh) + blob(2, 'msime-personal-features') + blob(5, weights)
    graph += blob(11, info('features', [16, 128])) + blob(12, info('embedding', [16, 64]))
    return integer(1, 8) + blob(2, 'msime') + blob(3, '1') + blob(7, graph) + blob(8, integer(2, 13))

if __name__ == '__main__':
    path = Path(__file__).resolve().parents[1] / 'crates/engine/src/adaptive/model.onnx'
    expected = model()
    import sys
    if '--check' in sys.argv:
        if path.read_bytes() != expected:
            raise SystemExit('个人排序 ONNX 图与生成器不一致')
    else:
        path.write_bytes(expected)
    print(f'{path}: {len(expected)} bytes')
