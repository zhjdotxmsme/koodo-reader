import toast from "react-hot-toast";
import {
  ConfigService,
  ReaderRequest,
} from "../../assets/lib/kookit-extra-browser.min";
import i18n from "../../i18n";
import { getServerRegion } from "../common";
import TokenService from "../storage/tokenService";
let readerRequest: ReaderRequest | undefined;
export const getReaderRequest = async () => {
  if (readerRequest) {
    return readerRequest;
  }
  readerRequest = new ReaderRequest(
    TokenService,
    ConfigService,
    getServerRegion()
  );
  return readerRequest;
};
export const resetReaderRequest = () => {
  readerRequest = undefined;
};
export const getOcrResult = async (imageBase64: string, options: any) => {
  let readerRequest = await getReaderRequest();
  let image = "data:image/png;base64," + imageBase64;
  let res = await readerRequest.getOcrResult(image);
  if (res.code === 200) {
    return res.data;
  } else {
    toast.error(i18n.t("Fetch failed, error code") + ": " + res.msg);
  }
  return "";
};
export const getOcrResultV2 = async (imageBase64: string, options: any) => {
  let readerRequest = await getReaderRequest();
  let binary = atob(imageBase64);
  let bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  let file = new Blob([bytes], { type: "image/png" });
  let res = await readerRequest.getOcrResultV2({ file });
  if (res.code === 200) {
    return res.data;
  } else {
    toast.error(i18n.t("Fetch failed, error code") + ": " + res.msg);
  }
  return "";
};
