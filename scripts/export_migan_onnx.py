"""Re-create models/migan.onnx from the official MI-GAN TorchScript (MIT).
curl -L -o migan_traced.pt https://github.com/Sanster/models/releases/download/migan/migan_traced.pt
python3 export_migan_onnx.py migan_traced.pt migan.onnx"""
import sys, torch
m = torch.jit.load(sys.argv[1], map_location="cpu").eval()
x = torch.zeros(1, 4, 512, 512)   # [0.5 - mask, image*(1-mask)] in [-1, 1]
torch.onnx.export(m, (x,), sys.argv[2], input_names=["input"], output_names=["output"], opset_version=17, dynamo=False)
