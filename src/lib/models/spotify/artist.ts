/**
 * This class is based on the artist response from the My Top Artists endpoint query.
 */
import {ExternalUrls, Image} from "./track";
import {deserialize} from "cerialize";

export class Artist {
    @deserialize external_urls: ExternalUrls;
    @deserialize followers: Followers;
    @deserialize genres: string[];
    @deserialize href: string;
    @deserialize id: string;
    @deserialize images: Image[];
    @deserialize name: string;
    @deserialize popularity: number;
    @deserialize type: string;
    @deserialize uri: string;
}


export class Followers {
    @deserialize href: null | string;
    @deserialize total: number;
}
