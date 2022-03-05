export default {
    /**
     * Returns an array of n-element subarrays created by splitting the given array up.
     */
    chunkedList: function <Type>(list: Type[], chunkSize: number): Type[][] {
        let start = 0
        let listOfLists = []
        while (start < list.length) {
            const sublist = list.slice(start, start + chunkSize)
            listOfLists.push(sublist)
            start += chunkSize
        }
        return listOfLists
    },

    /**
     * Shuffles array in place. ES6 version from https://stackoverflow.com/questions/6274339/how-can-i-shuffle-an-array
     * @param {Array} a items An array containing the items.
     */
    shuffle: function (a) {
        for (let i = a.length - 1; i > 0; i--) {
            const j = Math.floor(Math.random() * (i + 1));
            [a[i], a[j]] = [a[j], a[i]];
        }
        return a;
    }
};